package com.loanrun.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.error.ApiException;
import java.util.HashSet;
import java.util.Set;

/** Keeps omitted fields distinct from explicit JSON null; rejects identifier/unknown field injection. */
public final class JsonPatch {
    private JsonPatch() {}

    public static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) throw ApiException.validation("non_field_errors", "객체를 입력해 주세요.");
        Set<String> names = new HashSet<>(Set.of(allowed));
        node.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw ApiException.validation(name, "지원하지 않는 필드입니다.");
        });
    }

    public static String text(JsonNode node, String field, boolean nullable, int maxLength) {
        JsonNode value = node.get(field);
        if (nullable && value != null && value.isNull()) return null;
        if (value == null || !value.isTextual() || (!nullable && value.textValue().isBlank())
                || value.textValue().codePointCount(0, value.textValue().length()) > maxLength) {
            throw ApiException.validation(field, "문자열의 길이와 값을 확인해 주세요.");
        }
        return value.textValue();
    }

    public static boolean bool(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isBoolean()) throw ApiException.validation(field, "boolean 값이어야 합니다.");
        return node.get(field).booleanValue();
    }

    public static <E extends Enum<E>> E enumeration(JsonNode node, String field, Class<E> type) {
        String value = text(node, field, false, 60);
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException ex) { throw ApiException.validation(field, "지원하지 않는 값입니다."); }
    }
}
