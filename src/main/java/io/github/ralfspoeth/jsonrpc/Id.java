package io.github.ralfspoeth.jsonrpc;

public sealed interface Id {
    record IntId(int id) implements Id {
        @Override
        public String toString() {
            return Integer.toString(id);
        }
    }
    record StringId(String id) implements Id {
        @Override
        public String toString() {
            return id;
        }
    }
}
