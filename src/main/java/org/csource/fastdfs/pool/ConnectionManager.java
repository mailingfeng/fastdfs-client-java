package org.csource.fastdfs.pool;

import org.csource.common.MyException;
import org.csource.fastdfs.ClientGlobal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionManager {

    private InetSocketAddress inetSocketAddress;

    /**
     * total create connection pool
     */
    private AtomicInteger totalCount = new AtomicInteger();

    /**
     * free connection count
     */
    private AtomicInteger freeCount = new AtomicInteger();

    /**
     * lock
     */
    private ReentrantLock lock = new ReentrantLock(true);

    private Condition condition = lock.newCondition();

    /**
     * free connections
     */
    private volatile ConcurrentLinkedQueue<Connection> freeConnections = new ConcurrentLinkedQueue<Connection>();

    private ConnectionManager() {

    }

    public ConnectionManager(InetSocketAddress socketAddress) {
        this.inetSocketAddress = socketAddress;
    }

    public Connection getConnection() throws MyException {
        lock.lock();
        try {
            Connection connection = null;
            while (true) {
                if (freeCount.get() > 0) {
                    freeCount.decrementAndGet();
                    connection = freeConnections.poll();
                    if (!connection.isAvaliable() || (System.currentTimeMillis() - connection.getLastAccessTime()) > ClientGlobal.getG_connection_pool_max_idle_time()) {
                        closeConnection(connection);
                        continue;
                    }
                } else if (ClientGlobal.getG_connection_pool_max_count_per_entry() == 0 || totalCount.get() < ClientGlobal.getG_connection_pool_max_count_per_entry()) {
                    connection = ConnectionFactory.create(this.inetSocketAddress);
                    if (connection != null) {
                        totalCount.incrementAndGet();
                    }
                } else {
                    try {
                        if (condition.await(ClientGlobal.getG_connection_pool_max_wait_time_in_ms(), TimeUnit.MILLISECONDS)) {
                            //wait single success
                            continue;
                        }
                        throw new MyException("get connection fail,  wait_time greater than " + ClientGlobal.g_connection_pool_max_wait_time_in_ms + "ms");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        throw new MyException("get connection fail, emsg:" + e.getMessage());
                    }
                }
                return connection;
            }
        } finally {
            lock.unlock();
        }
    }

    public void releaseConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        lock.lock();
        try {
            connection.setLastAccessTime(System.currentTimeMillis());
            freeConnections.add(connection);
            freeCount.incrementAndGet();
            condition.signal();
        } finally {
            lock.unlock();
        }

    }

    public void closeConnection(Connection connection) {
        try {
            if (connection != null) {
                totalCount.decrementAndGet();
                connection.closeDirectly();
            }
        } catch (IOException e) {
            System.err.println("close socket error , msg:" + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public String toString() {
        return "ConnectionManager{" +
                "ip:port='" + inetSocketAddress.getHostName() + ":" + inetSocketAddress.getPort() +
                ", totalCount=" + totalCount +
                ", freeCount=" + freeCount +
                ", freeConnections =" + freeConnections +
                '}';
    }
}
