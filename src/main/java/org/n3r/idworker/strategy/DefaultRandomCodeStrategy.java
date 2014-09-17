package org.n3r.idworker.strategy;

import org.n3r.idworker.RandomCodeStrategy;
import org.n3r.idworker.utils.Serializes;
import org.n3r.idworker.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Queue;

public class DefaultRandomCodeStrategy implements RandomCodeStrategy {
    static final int MAX_PREFIX_INDEX = Integer.MAX_VALUE;
    public static final int MAX_BITS = 16777216;

    Logger log = LoggerFactory.getLogger(DefaultRandomCodeStrategy.class);

    File idWorkerHome = Utils.createIdWorkerHome();
    volatile FileLock fileLock;
    BitSet codesFilter;
    volatile FileOutputStream fileOutputStream;

    int prefixIndex = -1;
    File codePrefixIndex;

    int minRandomSize = 5;
    int maxRandomSize = 10;

    public DefaultRandomCodeStrategy() {
        destroyFileLockWhenShutdown();
    }

    @Override
    public void init() {
        release();

        while (++prefixIndex < MAX_PREFIX_INDEX) {
            if (tryUsePrefix()) break;
        }

        if (prefixIndex == MAX_PREFIX_INDEX)
            throw new RuntimeException("all prefixes are used up, the world maybe ends!");
    }

    public DefaultRandomCodeStrategy setMinRandomSize(int minRandomSize) {
        this.minRandomSize = minRandomSize;
        return this;
    }

    public DefaultRandomCodeStrategy setMaxRandomSize(int maxRandomSize) {
        this.maxRandomSize = maxRandomSize;
        return this;
    }

    protected boolean tryUsePrefix() {
        codePrefixIndex = new File(idWorkerHome, "code.prefix." + prefixIndex);

        if (!createPrefixIndexFile()) return false;
        if (!createFileLock()) return false;
        if (!createBloomFilter()) return false;

        log.info("get available prefix index {}", prefixIndex);

        createFileOut();

        return true;
    }

    private boolean createFileLock() {
        if (fileLock != null) fileLock.destroy();
        fileLock = new FileLock(codePrefixIndex);
        return fileLock.tryLock();
    }

    private void createFileOut() {
        Serializes.closeQuietly(fileOutputStream);
        try {
            fileOutputStream = new FileOutputStream(codePrefixIndex);
        } catch (FileNotFoundException e) {
            // ignore
        }
    }

    private boolean createBloomFilter() {
        codesFilter = Serializes.readObject(codePrefixIndex);
        if (codesFilter == null) {
            log.info("create new bloom filter");
            codesFilter = new BitSet(MAX_BITS); // 2^24
        } else {
            int size = codesFilter.cardinality();
            if (size > 16000000) {
                log.warn("bloom filter is already full");
                return false;
            }
            log.info("recreate bloom filter with capacity {}", size);
        }

        return true;
    }

    private void destroyFileLockWhenShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                release();
            }
        });
    }

    private boolean createPrefixIndexFile() {
        try {
            codePrefixIndex.createNewFile();
            return codePrefixIndex.exists();
        } catch (IOException e) {
            e.printStackTrace();
            log.warn("create file {} error {}", codePrefixIndex, e.getMessage());
        }
        return false;
    }

    @Override
    public int prefix() {
        return prefixIndex;
    }

    static final int CACHE_CODES_NUM = 1000;

    SecureRandom secureRandom = new SecureRandom();
    Queue<Integer> availableCodes = new ArrayDeque<Integer>(CACHE_CODES_NUM);

    @Override
    public int next() {
        if (availableCodes.isEmpty()) generate();

        return availableCodes.poll();
    }

    @Override
    public synchronized void release() {
        if (fileOutputStream == null) return;

        writeBitSetToFile();
        Serializes.closeQuietly(fileOutputStream);
        fileOutputStream = null;
        fileLock.destroy();
    }

    private void generate() {
        for (int i = 0; i < CACHE_CODES_NUM; ++i)
            availableCodes.add(generateOne());

        writeBitSetToFile();
    }

    private int generateOne() {
        while (true) {
            boolean found = true;
            int code = -1;

            for (int size = minRandomSize; found && size <= maxRandomSize; ++size) {
                code = secureRandom.nextInt(max(size));
                found = contains(code);
            }

            code = !found ? add(code) : tryFindAvailableCode(code);
            if (code >= 0) return code;

            init();
        }
    }

    private int tryFindAvailableCode(int code) {
        for (int i = code + 1; i < max(maxRandomSize); ++i) {
            if (contains(i)) continue;
            return add(i);
        }

        for (int i = 0; i < code; ++i) {
            if (contains(i)) continue;
            return add(i);
        }

        return -1;
    }

    private int add(int code) {
        codesFilter.set(code);
        return code;
    }

    private boolean contains(int code) {
        return codesFilter.get(code);
    }


    private int max(int size) {
        switch (size) {
            case 1: // fall through
            case 2: // fall through
            case 3: // fall through
            case 4:
                return 10000;
            case 5:
                return 100000;
            case 6:
                return 1000000;
            case 7:
                return 10000000;
            case 8:
                return 100000000;
            case 9:
                return 1000000000;
            default:
                return Integer.MAX_VALUE;
        }
    }

    private synchronized void writeBitSetToFile() {
        Serializes.writeObject(fileOutputStream, codesFilter);
    }

}