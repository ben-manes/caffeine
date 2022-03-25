package com.github.benmanes.caffeine.cache;

public class NodeAccessInfo {

    public static final int WINDOW = 0;
    public static final  int PROBATION = 1;
    public static final  int PROTECTED = 2;

    /** Returns if the entry is in the Window or Main space. */
    public boolean inWindow() {
        return getQueueType() == WINDOW;
    }

    /** Returns if the entry is in the Main space's probation queue. */
    public boolean inMainProbation() {
        return getQueueType() == PROBATION;
    }

    /** Returns if the entry is in the Main space's protected queue. */
    public boolean inMainProtected() {
        return getQueueType() == PROTECTED;
    }

    /** Sets the status to the Window queue. */
    public void makeWindow() {
        setQueueType(WINDOW);
    }

    /** Sets the status to the Main space's probation queue. */
    public void makeMainProbation() {
        setQueueType(PROBATION);
    }

    /** Sets the status to the Main space's protected queue. */
    public void makeMainProtected() {
        setQueueType(PROTECTED);
    }

    /** Returns the queue that the entry's resides in (window, probation, or protected). */
    public int getQueueType() {
        return WINDOW;
    }

    /** Set queue that the entry resides in (window, probation, or protected). */
    public void setQueueType(int queueType) {
        throw new UnsupportedOperationException();
    }
}
