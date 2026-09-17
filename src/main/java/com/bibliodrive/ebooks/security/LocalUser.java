package com.bibliodrive.ebooks.security;

import java.util.List;

public record LocalUser(String username, String passwordHash, List<String> roles) {
}
