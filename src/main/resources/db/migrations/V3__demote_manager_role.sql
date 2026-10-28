-- ERole.MANAGER was removed: no endpoint ever granted or checked it. A row that
-- still carries it could not be loaded (Hibernate cannot map an unknown enum
-- constant), so demote it to the least-privileged role.
UPDATE users SET role = 'USER' WHERE role = 'MANAGER';
