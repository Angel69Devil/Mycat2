/**
 * Copyright (C) <2019>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.proxy.session;

import io.mycat.Wrapper;
import io.mycat.proxy.handler.NIOHandler;
import io.mycat.proxy.reactor.MycatReactorThread;
import io.mycat.proxy.reactor.NIOJob;
import io.mycat.proxy.reactor.ReactorEnvThread;
import io.mycat.util.VertxUtil;
import io.vertx.core.impl.future.PromiseInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SocketChannel;

/**
 * @author jamie12221 chen junwen date 2019-05-10 21:13 Session
 **/
public interface Session<T extends Session> extends Wrapper {
   static final Logger LOGGER = LoggerFactory.getLogger(Session.class);
  /**
   * 通道
   */
  SocketChannel channel();

  /**
   * 判断session是否已经关闭,一般采用通道关闭来判断,判断该方法可以实现关闭幂等
   */
  boolean hasClosed();

  /**
   * 获取1当前session的处理句柄
   */
  NIOHandler getCurNIOHandler();

  static String getThrowableString(Throwable e) {
    StringWriter errors = new StringWriter();
    e.printStackTrace(new PrintWriter(errors));
    //去掉重复提示的消息 return MessageFormat.format("{0} \n {1}",e,errors.toString());
    return errors.toString();
  }

  /**
   * 该session的标识符,唯一
   * @return
   */
  long sessionId();

  /**
   * 获取该session,最近活跃的时间
   */
  void updateLastActiveTime();

  /**
   * 把session内buffer写入通道
   */
  PromiseInternal<Void> writeToChannel() throws IOException;

  /**
   * 会话关闭时候的的动作，需要清理释放资源
   */
  PromiseInternal<Void> close(boolean normal, String hint);

  /**
   * 读事件回调
   */
  boolean readFromChannel() throws IOException;


  /**
   * 注册读事件
   */
  void change2ReadOpts();

  /**
   * 注册写事件
   */
  void change2WriteOpts();

  /**
   * 获取当前线程池
   */
  MycatReactorThread getIOThread();
//
//  default ProxyRuntime getRuntime() {
//    MycatReactorThread thread = (MycatReactorThread) Thread.currentThread();
//    return thread.getRuntime();
//  }

  /**
   * 获取上下文设置的错误信息
   */
  String getLastMessage();

  String setLastMessage(String lastMessage);

  /**
   * session内buffer写入通道,根据一定条件判断数据写入完毕后回调方法
   */
  default PromiseInternal<Void>  writeFinished(T session) {
    session.getCurNIOHandler().onWriteFinished(session);
    return VertxUtil.newSuccessPromise();
  }

  default String setLastMessage(Throwable e) {
    LOGGER.error("",e);
    String string = getThrowableString(e);
    setLastMessage(string);
    return string;
  }

  /**
   * 设置回调函数,若果设置了回调,则该session的资源释放取决于回调代码什么时候结束,
   */

  default PromiseInternal<Void>  close(boolean normal, Exception hint) {
    return close(normal, getThrowableString(hint));
  }

  default PromiseInternal<Void>  lazyClose(boolean normal, String hint) {
    PromiseInternal<Void> promise = VertxUtil.newPromise();
    getIOThread().addNIOJob(new NIOJob() {
      @Override
      public void run(ReactorEnvThread reactor) throws Exception {
        close(normal, hint).onComplete(o-> promise.tryComplete());
      }

      @Override
      public void stop(ReactorEnvThread reactor, Exception reason) {
        close(normal, hint).onComplete(o-> promise.tryComplete());
      }

      @Override
      public String message() {
        return hint;
      }
    });
    return promise;
  }

  default long currentTimeMillis() {
    return getIOThread().getLastActiveTime();
  }

  void clearReadWriteOpts();
}
