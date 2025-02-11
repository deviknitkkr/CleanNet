package com.deviknitkkr.clean_net.blocklist;

import java.util.HashMap;
import java.util.Map;

class TrieNode {
    Map<String, TrieNode> children = new HashMap<>();
    boolean isWildcard = false; // Indicates if this node represents a wildcard
}