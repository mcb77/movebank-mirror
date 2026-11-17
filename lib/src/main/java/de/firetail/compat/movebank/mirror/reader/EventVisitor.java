package de.firetail.compat.movebank.mirror.reader;

/** Receives the events of a {@link MirrorReader#readEvents} call, in Movebank's order. */
public interface EventVisitor {

    /**
     * Called each time a source file is opened, before its rows, even if none of
     * its rows is accepted. Lets a writer emit a header on the first file, as
     * movebank-mirror-api does.
     */
    default void file(EventColumns columns) throws Exception {
    }

    void event(Event event) throws Exception;
}
