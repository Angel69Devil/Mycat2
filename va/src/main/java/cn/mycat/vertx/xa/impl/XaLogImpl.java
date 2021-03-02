/**
 * Copyright [2021] [chen junwen]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.*;
import io.vertx.core.*;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class XaLogImpl implements XaLog {
    private final static Logger LOGGER = LoggerFactory.getLogger(XaLogImpl.class);
    private final String name;
    private final Repository xaRepository;
    private final AtomicLong xaIdSeq;
    private final MySQLManager mySQLManager;

    public static XaLogImpl createXaLogImpl(Repository xaRepository, MySQLManager mySQLManager) {
        XaLogImpl xaLog = new XaLogImpl(xaRepository, 0,mySQLManager);
        return xaLog;
    }

    public XaLogImpl(Repository xaRepository,int workerId, MySQLManager mySQLManager) {
        this("x", xaRepository, '.', workerId, mySQLManager);
    }

    public XaLogImpl(String name, Repository xaRepository, char sep, int workerId, MySQLManager mySQLManager) {
        this.mySQLManager = mySQLManager;
        this.name = name + sep;
        this.xaRepository = xaRepository;
        this.xaIdSeq = new AtomicLong(System.currentTimeMillis()<<4 + workerId);

    }

    public static XaLog createXaLog(MySQLManager mySQLManager) {
        return new XaLogImpl(new MemoryRepositoryImpl(),0, mySQLManager);
    }


    public Future<Object> recoverConnection(ImmutableCoordinatorLog entry) {
        return Future.future(res -> {
            if (entry.mayContains(State.XA_COMMITED)) {
                commit(res, entry);
                return;
            }
            Map<String, State> map = new ConcurrentHashMap<>();
            for (ImmutableParticipantLog participant : entry.getParticipants()) {
                map.put(participant.getTarget(), participant.getState());
            }
            boolean hasCommitted = !entry.mayContains(State.XA_ENDED);
            List<Future> data = entry.getParticipants().stream().map(i -> {
                return mySQLManager.getConnection(i.getTarget())
                        .flatMap(c -> {
                            Future<RowSet<Row>> execute = c.query((XaSqlConnection.XA_RECOVER)).execute();
                            return execute.compose(rows -> Future.succeededFuture(StreamSupport.stream(rows.spliterator(), false)
                                    .map(i1 -> i1.getString("data"))
                                    .filter(i1 -> i1 != null)
                                    .filter(i1 -> entry.getXid().equals(i1))
                                    .findFirst().map(i12 -> State.XA_PREPARED)
                                    .orElse(hasCommitted ? State.XA_COMMITED : State.XA_ENDED)))
                                    .map(now -> {
                                        map.compute(i.getTarget(), (s, old) -> old.compareTo(now) < 0 ? now : old);
                                        c.close();
                                        return now;
                                    });
                        });
            }).collect(Collectors.toList());
            CompositeFuture.all(data).onComplete(new Handler<AsyncResult<CompositeFuture>>() {
                @Override
                public void handle(AsyncResult<CompositeFuture> event) {
                    ArrayList<ImmutableParticipantLog> participantLogs = new ArrayList<>();
                    for (Map.Entry<String, State> stringStateEntry : map.entrySet()) {
                        String key = stringStateEntry.getKey();
                        State value = stringStateEntry.getValue();
                        participantLogs.add(new ImmutableParticipantLog(key, getExpires(), value));
                    }

                    ImmutableCoordinatorLog immutableCoordinatorLog = new ImmutableCoordinatorLog(entry.getXid(), participantLogs.toArray(new ImmutableParticipantLog[]{}));
                    if (immutableCoordinatorLog.mayContains(State.XA_COMMITED)) {
                        commit(res, immutableCoordinatorLog);
                    } else {
                        rollback(res, immutableCoordinatorLog);
                    }

                }
            });
        });
    }

    private void commit(Promise<Object> res, ImmutableCoordinatorLog entry) {
        List<Future> list = new ArrayList<>();
        for (ImmutableParticipantLog participant : entry.getParticipants()) {
            if (participant.getState() == State.XA_PREPARED) {
                list.add(mySQLManager.getConnection(participant.getTarget())
                        .compose(sqlConnection -> {
                            Future<SqlConnection> future = Future.succeededFuture(sqlConnection);
                            return future
                                    .compose(connection -> connection.query(
                                            String.format(XaSqlConnection.XA_COMMIT, entry.getXid())

                                    ).execute().map(c -> {
                                        Future<Void> closeFuture = sqlConnection.close();
                                        try {
                                            log(entry.getXid(), participant.getTarget(), State.XA_COMMITED);
                                            checkState(entry.getXid(), true, State.XA_COMMITED);
                                        } catch (Exception e) {
                                            return CompositeFuture.all(closeFuture, Future.failedFuture(e));
                                        }
                                        return closeFuture;
                                    }));
                        }));
            } else {
                list.add(Future.succeededFuture());
            }
        }
        CompositeFuture.all(list).onComplete(unused -> {
            try {
                logCommit(entry.getXid(), unused.succeeded());
            } catch (Exception e) {
                res.fail(e);
                return;
            }
            res.tryComplete();
        });
    }

    private void rollback(Promise<Object> res, ImmutableCoordinatorLog entry) {
        List<Future> list = new ArrayList<>();
        for (ImmutableParticipantLog participant : entry.getParticipants()) {
            if (participant.getState() == State.XA_PREPARED || participant.getState() == State.XA_ENDED) {
                list.add(mySQLManager.getConnection(participant.getTarget())
                        .compose(sqlConnection -> {
                            Future<SqlConnection> future = Future.succeededFuture(sqlConnection);
                            return future
                                    .compose(connection -> connection.query(
                                            String.format(XaSqlConnection.XA_ROLLBACK, entry.getXid())

                                    ).execute().map(c -> {
                                        Future<Void> closeFuture = sqlConnection.close();
                                        try {
                                            log(entry.getXid(), participant.getTarget(), State.XA_ROLLBACKED);
                                            checkState(entry.getXid(), true, State.XA_ROLLBACKED);
                                        } catch (Exception e) {
                                            return CompositeFuture.all(closeFuture, Future.failedFuture(e));
                                        }
                                        return closeFuture;
                                    }));
                        }));
            } else {
                list.add(Future.succeededFuture());
            }
            CompositeFuture.all(list).onComplete(unused -> {
                try {
                    logRollback(entry.getXid(), unused.succeeded());
                    res.tryComplete();
                } catch (Exception e) {
                    res.tryFail(e);
                }

            });
        }
    }

    public Future<Void> readXARecoveryLog() {
        Future<Void> future = mySQLManager.getConnectionMap().flatMap(stringSqlConnectionMap -> CompositeFuture.all(
                stringSqlConnectionMap.values().stream()
                        .map(c -> LocalXaMemoryRepositoryImpl.tryCreateLogTable(c)
                                .onComplete(unused -> c.close())).collect(Collectors.toList())).mapEmpty());
        return future.flatMap(unused -> {
            ConcurrentHashMap<String, Set<String>> xid_targets = new ConcurrentHashMap<>();//xid,Set<targetName>
            List<Future> futureList = new ArrayList<>();
            mySQLManager.getConnectionMap().onSuccess(event -> {
                for (Map.Entry<String, SqlConnection> entry : event.entrySet()) {
                    String targetName = entry.getKey();
                    futureList.add(entry.getValue().query("XA RECOVER").execute().flatMap(event1 -> {
                        RowIterator<Row> iterator = event1.iterator();
                        while (iterator.hasNext()) {
                            Row next = iterator.next();
                            Set<String> targetNameSet = xid_targets.computeIfAbsent(next.getString("data"), (s) -> new ConcurrentHashSet<>());
                            targetNameSet.add(targetName);
                        }
                        return entry.getValue().close();
                    }));
                }
            });
            Set<String> xidSet = new ConcurrentHashSet<>();//xid set
            mySQLManager.getConnectionMap().onSuccess(event -> {
                for (Map.Entry<String, SqlConnection> entry : event.entrySet()) {
                    futureList.add(entry.getValue().query("select xid from mycat.xa_log").execute().flatMap(event1 -> {
                        RowIterator<Row> iterator = event1.iterator();
                        while (iterator.hasNext()) {
                            Row next = iterator.next();
                            xidSet.add(next.getString("xid"));
                        }
                        return entry.getValue().close();
                    }));
                }
            });
            return CompositeFuture.all(futureList).flatMap(unuse -> {
                List<Future<Void>> futureList1 = new ArrayList<>();
                for (Map.Entry<String, Set<String>> entry : xid_targets.entrySet()) {
                    String xid = entry.getKey();
                    Set<String> targets = entry.getValue();
                    String sql;
                    if (xidSet.contains(xid)) {
                        sql = "XA COMMIT '" + xid + "'";
                    } else {
                        sql = "XA ROLLBACK '" + xid + "'";
                    }
                    for (String target : targets) {
                        futureList1.add(mySQLManager.getConnection(target)
                                .flatMap(sqlConnection -> sqlConnection.query(sql)
                                        .execute()
                                        .flatMap((Function<RowSet<Row>, Future<Void>>) rows ->
                                                sqlConnection.query("delete from mycat.xa_log where xid = '"+xid+"'").execute()
                                                        .mapEmpty())
                                        .onComplete(u -> {
                                            sqlConnection.close().mapEmpty();
                                        }).mapEmpty()));
                    }
                }
                return CompositeFuture.all((List) futureList1);
            }).onFailure(event -> System.out.println(event)).mapEmpty();
        });
    }


    @Override
    public String nextXid() {
        long seq = xaIdSeq.getAndUpdate(operand -> {
            if (operand < 0) {
                return 0;
            }
            return ++operand;
        });
        return name + seq;
    }

    @Override
    public long getTimeout() {
        return xaRepository.getTimeout();
    }

    @Override
    public void log(String xid, ImmutableParticipantLog[] participantLogs) {
        if (xid == null) return;
        synchronized (xaRepository) {
            ImmutableCoordinatorLog immutableCoordinatorLog = new ImmutableCoordinatorLog(xid, participantLogs);
            xaRepository.put(xid, immutableCoordinatorLog);
        }
    }

    @Override
    public void log(String xid, String target, State state) {
        if (xid == null) return;
        synchronized (xaRepository) {
            ImmutableCoordinatorLog coordinatorLog = xaRepository.get(xid);
            if (coordinatorLog == null) {
                log(xid, new ImmutableParticipantLog[]{new ImmutableParticipantLog(target,
                        getExpires(), state)});
            } else {
                boolean hasCommited = coordinatorLog.mayContains(State.XA_COMMITED);
                ImmutableParticipantLog[] logs = coordinatorLog.replace(target, state, getExpires());
                if (state == State.XA_COMMITED && !hasCommited) {
                    log(xid, logs);
                } else {
                    log(xid, logs);
                }
            }
        }
    }

    @Override
    public void logRollback(String xid, boolean succeed) {
        if (xid == null) return;
        //only log
        if (!checkState(xid, succeed, State.XA_ROLLBACKED)) {
            LOGGER.error("check logRollback xid:" + xid + " error");
        } else if (succeed) {
            synchronized (xaRepository) {
                xaRepository.remove(xid);
            }
        }
    }

    @Override
    public void logPrepare(String xid, boolean succeed) {
        if (xid == null) return;
        //only log
        if (!checkState(xid, succeed, State.XA_PREPARED)) {
            LOGGER.error("check logPrepare xid:" + xid + " error");
        }
    }

    private boolean checkState(String xid, boolean succeed, State state) {
        boolean s;
        ImmutableCoordinatorLog coordinatorLog = xaRepository.get(xid);

        if (coordinatorLog != null) {
            State recordState = coordinatorLog.computeMinState();
            s = (succeed == (recordState == state)) || (succeed && (recordState == State.XA_COMMITED || recordState == State.XA_ROLLBACKED));
        } else {
            s = true;
        }
        return s;
    }

    @Override
    public void logCommit(String xid, boolean succeed) {
        if (xid == null) return;
        //only log
        if (!checkState(xid, succeed, State.XA_COMMITED)) {
            LOGGER.error("check logCommit xid:" + xid + " error");
        } else if (succeed) {
            synchronized (xaRepository) {
                xaRepository.remove(xid);
            }
        }
    }

    @Override
    public ImmutableCoordinatorLog logCommitBeforeXaCommit(String xid) {
        if (xid == null) return null;
        //only log

        synchronized (xaRepository) {
            ImmutableCoordinatorLog immutableCoordinatorLog = xaRepository.get(xid);
            immutableCoordinatorLog.withCommit(true);
            xaRepository.writeCommitLog(immutableCoordinatorLog);
            return immutableCoordinatorLog;
        }
    }


    @Override
    public void logCancelCommitBeforeXaCommit(String xid) {
        if (xid == null) return;
        //only log
        synchronized (xaRepository) {
            xaRepository.cancelCommitLog(xid);
        }
    }

    @Override
    public void beginXa(String xid) {
        if (xid == null) return;
        //only log
    }

    @Override
    public long getExpires() {
        return getTimeout() + System.currentTimeMillis();
    }

    @Override
    public long retryDelay() {
        return xaRepository.retryDelayTime();
    }

    @Override
    public void close() throws IOException {
        xaRepository.close();
        mySQLManager.close();
    }
}
