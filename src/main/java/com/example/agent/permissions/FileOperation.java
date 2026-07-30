package com.example.agent.permissions;

public enum FileOperation {
    LIST,
    SEARCH,
    READ,
    CREATE,
    EDIT,
    DELETE;

    public boolean isMutation() {
        return this == CREATE || this == EDIT || this == DELETE;
    }
}
