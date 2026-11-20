<?php

declare(strict_types=1);

namespace Maggie\Core\Security;

use Doctrine\DBAL\Connection;
use Symfony\Component\Uid\Ulid;

/**
 * Seals the connected-service tokens already in the database, or opens them
 * back (MAG-889) — the migration's two directions, in plain SQL so that it
 * does not depend on the entities of the day. The contexts are the entities'
 * own: ids in base32, as `(string) $ulid` gives them.
 */
final class ExistingTokensSealer
{
    /** table → [owner column, token columns] */
    private const COLUMNS = [
        '"user"' => ['user' => 'id', 'context' => 'user', 'tokens' => ['google_access_token', 'google_refresh_token']],
        'bank_connection' => ['user' => 'user_id', 'context' => 'bank_connection', 'tokens' => ['session_id']],
    ];

    /** Whether at least one token is stored in plain. */
    public static function hasPlainTokens(Connection $db): bool
    {
        foreach (self::rows($db) as [, , , $values]) {
            foreach ($values as $value) {
                if (null !== $value && !TokenCipher::isSealed($value)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Seals every plain token; one already sealed is left as is. Returns the rows changed. */
    public static function seal(Connection $db, TokenCipher $cipher): int
    {
        return self::rewrite($db, static fn (string $value, string $context): ?string => TokenCipher::isSealed($value) ? null : $cipher->seal($value, $context));
    }

    /** Opens every sealed token back to plain. Returns the rows changed. */
    public static function open(Connection $db, TokenCipher $cipher): int
    {
        return self::rewrite($db, static fn (string $value, string $context): ?string => TokenCipher::isSealed($value) ? $cipher->open($value, $context) : null);
    }

    /** @param \Closure(string, string): ?string $transform the new value, null to leave it */
    private static function rewrite(Connection $db, \Closure $transform): int
    {
        $changed = 0;

        foreach (self::rows($db) as [$table, $id, $owner, $values]) {
            $updates = [];
            foreach ($values as $column => $value) {
                if (null === $value) {
                    continue;
                }
                $context = sprintf('%s:%s:%s:%s', self::COLUMNS[$table]['context'], $id, $owner, $column);
                $new = $transform($value, $context);
                if (null !== $new) {
                    $updates[$column] = $new;
                }
            }

            if ([] !== $updates) {
                $db->update($table, $updates, ['id' => Ulid::fromString($id)->toRfc4122()]);
                ++$changed;
            }
        }

        return $changed;
    }

    /** @return iterable<array{string, string, string, array<string, ?string>}> table, record id, owner id (base32), token columns */
    private static function rows(Connection $db): iterable
    {
        foreach (self::COLUMNS as $table => $columns) {
            $select = sprintf('SELECT id, %s AS owner_id, %s FROM %s', $columns['user'], implode(', ', $columns['tokens']), $table);
            foreach ($db->fetchAllAssociative($select) as $row) {
                yield [
                    $table,
                    (string) Ulid::fromString($row['id']),
                    (string) Ulid::fromString($row['owner_id']),
                    array_intersect_key($row, array_flip($columns['tokens'])),
                ];
            }
        }
    }
}
