package de.firetail.compat.movebank.mirror.reader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The header of one event CSV file, with a name-to-index lookup. */
public final class EventColumns {

    private final String[] names;
    private final List<String> list;
    private final Map<String, Integer> index = new HashMap<>();

    EventColumns(String[] names) {
        this.names = names;
        this.list = List.of(names);
        for (int i = names.length - 1; i >= 0; i--) index.put(names[i], i); // first occurrence wins
    }

    /** The column names, in file order; equal for files with the same header. */
    public List<String> names() { return list; }

    public int indexOf(String column) {
        Integer i = index.get(column);
        return i == null ? -1 : i;
    }
}
