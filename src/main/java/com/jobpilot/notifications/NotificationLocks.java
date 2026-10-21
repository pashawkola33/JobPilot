package com.jobpilot.notifications;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
public final class NotificationLocks {
    private final JdbcTemplate jdbc;
    private NotificationLocks(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public static NotificationLocks bound(JdbcTemplate jdbc){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification lock requires transaction");
        boolean postgres=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        if(!postgres)return new NotificationLocks(jdbc);
        jdbc.execute("set local lock_timeout='1000ms'");
        jdbc.execute("set local statement_timeout='2000ms'");
        jdbc.execute("set local idle_in_transaction_session_timeout='12000ms'");
        return new NotificationLocks(jdbc);
    }
    public void lockCandidate(long id){jdbc.queryForObject("select id from candidates where id=? for update",Long.class,id);}
}
