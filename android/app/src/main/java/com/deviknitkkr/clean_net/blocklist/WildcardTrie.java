package com.deviknitkkr.clean_net.blocklist;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WildcardTrie {
    private final TrieNode root = new TrieNode();

    /**
     * Inserts a domain pattern into the Trie.
     *
     * @param domainPattern The domain pattern (e.g., "*.example.com").
     */
    public void insert(String domainPattern) {
        if (domainPattern == null || domainPattern.isEmpty()) {
            return; // Skip invalid patterns
        }

        List<String> parts = splitAndReverseDomain(domainPattern);
        TrieNode current = root;

        for (String part : parts) {
            if (part.equals("*")) {
                current.isWildcard = true; // Mark this node as a wildcard
                break; // Wildcard matches all subdomains, so no need to go deeper
            }
            current = current.children.computeIfAbsent(part, k -> new TrieNode());
        }
    }

    /**
     * Checks if a domain matches any pattern in the Trie.
     *
     * @param domain The domain to check (e.g., "sub.example.com").
     * @return True if the domain matches a pattern, false otherwise.
     */
    public boolean matches(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false; // Skip invalid domains
        }

        List<String> parts = splitAndReverseDomain(domain);
        return matchesRecursive(root, parts, 0);
    }

    /**
     * Recursively checks if a domain matches any pattern in the Trie.
     *
     * @param node  The current Trie node.
     * @param parts The domain parts in reverse order.
     * @param index The current index in the parts list.
     * @return True if the domain matches a pattern, false otherwise.
     */
    private boolean matchesRecursive(TrieNode node, List<String> parts, int index) {
        if (node.isWildcard) {
            return true; // Wildcard matches all subdomains
        }
        if (index == parts.size()) {
            return false; // Reached the end of the domain without a match
        }

        String part = parts.get(index);

        // Check if the current part matches a child node
        if (node.children.containsKey(part)) {
            if (matchesRecursive(node.children.get(part), parts, index + 1)) {
                return true; // Match found
            }
        }

        // Check if there's a wildcard child node
        if (node.children.containsKey("*")) {
            if (matchesRecursive(node.children.get("*"), parts, index + 1)) {
                return true; // Wildcard match found
            }
        }

        return false; // No match found
    }

    /**
     * Splits a domain into parts and reverses the order.
     *
     * @param domain The domain (e.g., "sub.example.com").
     * @return A list of domain parts in reverse order (e.g., ["com", "example", "sub"]).
     */
    private List<String> splitAndReverseDomain(String domain) {
        String[] parts = domain.split("\\.");
        Collections.reverse(Arrays.asList(parts));
        return Arrays.asList(parts);
    }

    /**
     * Represents a node in the Trie.
     */
    private static class TrieNode {
        Map<String, TrieNode> children = new HashMap<>();
        boolean isWildcard = false; // Indicates if this node represents a wildcard
    }
}