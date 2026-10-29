package org.mydreamduels.dreammultiverse.world;

/** Which kinds of vanilla portal may be created in a world. */
public enum PortalForm {
    ALL, NETHER, END, NONE;

    public boolean allowsNether() {
        return this == ALL || this == NETHER;
    }

    public boolean allowsEnd() {
        return this == ALL || this == END;
    }
}
