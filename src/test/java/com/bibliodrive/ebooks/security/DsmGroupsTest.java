package com.bibliodrive.ebooks.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DsmGroupsTest {

    @Test
    void withoutGroupsEveryoneEntersAndFallbackAdminsAdminister(@TempDir Path dir) throws Exception {
        Path group = Files.writeString(dir.resolve("group"), "administrators:x:101:admin,danthalion\n");
        DsmGroups groups = new DsmGroups(group.toString(), "bibliolocal-admin", "bibliolocal-user", "Danthalion");
        assertEquals(DsmGroups.Role.ADMIN, groups.roleOf("danthalion"));
        assertEquals(DsmGroups.Role.USER, groups.roleOf("ana"));
    }

    @Test
    void withGroupsOnlyMembersEnter(@TempDir Path dir) throws Exception {
        Path group = Files.writeString(dir.resolve("group"),
                "bibliolocal-admin:x:65540:danthalion\nbibliolocal-user:x:65541:Ana,carlos\n");
        DsmGroups groups = new DsmGroups(group.toString(), "bibliolocal-admin", "bibliolocal-user", "");
        assertEquals(DsmGroups.Role.ADMIN, groups.roleOf("DANTHALION"));
        assertEquals(DsmGroups.Role.USER, groups.roleOf("ana"));
        assertEquals(DsmGroups.Role.NONE, groups.roleOf("pablo"));
    }
}
