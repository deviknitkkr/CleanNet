package com.deviknitkkr.clean_net;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class AppLogBuffer {
    private static final int MAX_ENTRIES = 128;
    private static final AppLogBuffer INSTANCE = new AppLogBuffer();
    private static final DateFormat DF = DateFormat.getTimeInstance(DateFormat.MEDIUM);

    private final String[] entries = new String[MAX_ENTRIES];
    private int index = 0;
    private int count = 0;

    public static AppLogBuffer getInstance() {
        return INSTANCE;
    }

    public synchronized void log(String tag, String message) {
        String ts = DF.format(new Date());
        entries[index] = "[" + ts + "][" + tag + "] " + message;
        index = (index + 1) % MAX_ENTRIES;
        if (count < MAX_ENTRIES) count++;
    }

    public synchronized List<String> getLogs() {
        List<String> result = new ArrayList<>(count);
        int start = count < MAX_ENTRIES ? 0 : index;
        for (int i = 0; i < count; i++) {
            result.add(entries[(start + i) % MAX_ENTRIES]);
        }
        return result;
    }

    public synchronized void clear() {
        index = 0;
        count = 0;
    }
}
