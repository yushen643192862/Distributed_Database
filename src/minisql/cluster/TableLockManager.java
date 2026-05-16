package minisql.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TableLockManager {
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public TableLocks lockTables(Collection<String> tableNames, boolean write) {
        List<String> names = normalizedNames(tableNames);
        List<Lock> acquired = new ArrayList<>();
        for (String name : names) {
            ReentrantReadWriteLock tableLock = locks.computeIfAbsent(name, key -> new ReentrantReadWriteLock(true));
            Lock lock = write ? tableLock.writeLock() : tableLock.readLock();
            lock.lock();
            acquired.add(lock);
        }
        return new TableLocks(acquired);
    }

    private List<String> normalizedNames(Collection<String> tableNames) {
        List<String> names = new ArrayList<>();
        if (tableNames != null) {
            for (String tableName : tableNames) {
                if (tableName != null && !tableName.isBlank()) {
                    names.add(tableName.toLowerCase(Locale.ROOT));
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    public static class TableLocks implements AutoCloseable {
        private final List<Lock> locks;

        private TableLocks(List<Lock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            for (int i = locks.size() - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }
}
