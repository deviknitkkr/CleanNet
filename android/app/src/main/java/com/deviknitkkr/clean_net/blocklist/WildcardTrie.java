package com.deviknitkkr.clean_net.blocklist;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WildcardTrie {
    private final TrieNode root = new TrieNode();

    public void insert(String domainPattern) {
        if (domainPattern == null || domainPattern.isEmpty()) {
            return;
        }

        String[] parts = domainPattern.split("\\.");
        int len = parts.length;

        if (len > 0 && "*".equals(parts[len - 1])) {
            return;
        }

        TrieNode current = root;

        for (int i = len - 1; i >= 0; i--) {
            String part = parts[i];
            if (part.equals("*")) {
                current = current.children.computeIfAbsent("*", k -> new TrieNode());
                current.isWildcard = true;
                return;
            }
            current = current.children.computeIfAbsent(part, k -> new TrieNode());
        }
        current.isWildcard = true;
    }

    public boolean matches(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }

        String[] parts = domain.split("\\.");
        return matchesRecursive(root, parts, parts.length - 1);
    }

    private boolean matchesRecursive(TrieNode node, String[] parts, int i) {
        if (node.isWildcard) {
            return true;
        }
        if (i < 0) {
            return false;
        }

        String part = parts[i];

        TrieNode child = node.children.get(part);
        if (child != null && matchesRecursive(child, parts, i - 1)) {
            return true;
        }

        child = node.children.get("*");
        if (child != null && matchesRecursive(child, parts, i - 1)) {
            return true;
        }

        return false;
    }

    private static class TrieNode {
        Map<String, TrieNode> children = new HashMap<>();
        boolean isWildcard = false;
    }
}
