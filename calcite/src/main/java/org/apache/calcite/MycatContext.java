package org.apache.calcite;

import io.mycat.Authenticator;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatDataContext;
import io.mycat.MycatUser;

public class MycatContext {
    public Object[] values;
    public static final ThreadLocal<MycatDataContext> CONTEXT = ThreadLocal.withInitial(() -> null);
    public Object getVariable(String name){
        return CONTEXT.get().getVariable(name);
    }
    public String getDatabase(){
        return CONTEXT.get().getDefaultSchema();
    }
    public Long getLastInsertId(){
        return CONTEXT.get().getLastInsertId();
    }
    public Long getConnectionId(){
        return CONTEXT.get().getSessionId();
    }
    public String getCurrentUser(){
        MycatUser user = CONTEXT.get().getUser();
        Authenticator authenticator = MetaClusterCurrent.wrapper(Authenticator.class);
        return user.getUserName()+"@"+authenticator.getUserInfo(user.getUserName()).getIp();
    }

    public String getUser(){
        MycatUser user = CONTEXT.get().getUser();
        return user.getUserName()+"@"+user.getHost();
    }
}