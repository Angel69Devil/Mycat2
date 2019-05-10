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
package io.mycat.proxy;

import io.mycat.MySQLDataNode;
import io.mycat.proxy.buffer.ProxyBuffer;
import io.mycat.proxy.packet.MySQLPacket;
import io.mycat.proxy.packet.MySQLPacketResolver;
import io.mycat.proxy.session.MySQLClientSession;
import io.mycat.proxy.session.MySQLProxySession.WriteHandler;
import io.mycat.proxy.session.MycatSession;
import java.io.IOException;

public enum MySQLProxyHandler {
  INSTANCE;

  public boolean handle(MycatSession mycat) throws IOException {
    mycat.switchWriteHandler(WriteHandler.INSTANCE);
    ProxyBuffer proxyBuffer = mycat.currentProxyBuffer();
    proxyBuffer.channelWriteStartIndex(0);
    proxyBuffer.channelWriteEndIndex(proxyBuffer.channelReadEndIndex());
    MySQLDataNode dataNode = (MySQLDataNode) MycatRuntime.INSTANCE
                                                 .getDataNodeByName(mycat.getDataNode());
    writeProxyBufferToDataNode(mycat, proxyBuffer, dataNode);
    return false;
  }

  public void writeProxyBufferToDataNode(
      MycatSession mycat,
      ProxyBuffer proxyBuffer,
      MySQLDataNode dataNode) {
    mycat.getBackend(false, dataNode, null,
        (mysql, sender, success, result, throwable) -> {
          if (success) {
            mycat.clearReadWriteOpts();
            try {
              mysql.writeProxyBufferToChannel(proxyBuffer);
            } catch (IOException e) {
              e.printStackTrace();
              //mycat.closeAllBackendsAndResponseError("");
            }
          } else {
            System.out.println("---------------------------------------------------------");
         //   mycat.closeAllBackendsAndResponseError("");
          }
        });
  }

  public boolean onBackendResponse(MySQLClientSession mysql) throws IOException {
    if (!mysql.readFromChannel()) {
      return false;
    }
    ProxyBuffer proxyBuffer = mysql.currentProxyBuffer();
    MySQLPacket mySQLPacket = (MySQLPacket) proxyBuffer;
    MySQLPacketResolver packetResolver = mysql.getPacketResolver();

    int startIndex = mySQLPacket.packetReadStartIndex();
    int endPos = startIndex;
    while (mysql.readPartProxyPayload()) {
      endPos = packetResolver.getEndPos();
      mySQLPacket.packetReadStartIndex(endPos);
    }
    proxyBuffer.channelWriteStartIndex(startIndex);
    proxyBuffer.channelWriteEndIndex(endPos);
    mysql.getMycatSession().writeToChannel();
    return false;
  }


  public boolean onBackendClosed(MySQLClientSession session, boolean normal) throws IOException {
    return false;
  }


  public boolean onFrontWriteFinished(MycatSession mycat) throws IOException {
    MySQLClientSession mysql = mycat.getBackend();
    if (mysql.isResponseFinished()) {
      mycat.change2ReadOpts();
      mysql.clearReadWriteOpts();
      mycat.resetPacket();
      mysql.unbindMycatIfNeed(mycat);
      return true;
    } else {
      mysql.change2ReadOpts();
      mycat.clearReadWriteOpts();
      ProxyBuffer proxyBuffer = mycat.currentProxyBuffer();
      int writeEndIndex = proxyBuffer.channelWriteEndIndex();
      proxyBuffer.channelReadStartIndex(writeEndIndex);
    }
    return false;
  }


  public boolean onBackendWriteFinished(MySQLClientSession mysql) throws IOException {
    MycatSession mycat = mysql.getMycatSession();
    if (true) {
      //mysql.clearReadWriteOpts();
      mysql.currentProxyBuffer().reset();
      mysql.currentProxyBuffer().newBuffer();
      mysql.prepareReveiceResponse();
      mysql.change2ReadOpts();
    } else {
      // mycat.clearReadWriteOpts();
      mysql.change2WriteOpts();
      mysql.writeToChannel();
    }
    return false;
  }


  public void clearResouces(MycatSession mycat, boolean sessionCLosed) {
    MySQLClientSession backend = mycat.getBackend();
    backend.unbindMycatIfNeed(mycat);
  }


  public void clearResouces(MySQLClientSession mysql, boolean sessionCLosed) {

  }

}
