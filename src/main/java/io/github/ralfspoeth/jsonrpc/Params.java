package io.github.ralfspoeth.jsonrpc;

import java.util.List;
import java.util.Map;

sealed interface Params {
    record ArrayParams(List<?> params) implements Params {}
    record MapParams(Map<String, ?> params) implements Params {}
}
