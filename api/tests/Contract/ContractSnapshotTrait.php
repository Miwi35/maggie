<?php

declare(strict_types=1);

namespace App\Tests\Contract;

/**
 * Reads and writes the files under api/contract/.
 *
 * Those files are the API's published contract: what the admin, the mobile app
 * and the agent are entitled to rely on. A test that changes one of them fails,
 * and the only way to make it pass is to regenerate the file and commit it —
 * which puts the change in the diff, where a reviewer sees it. That is the
 * whole point: nothing here stops a breaking change, it stops an *unnoticed*
 * one.
 *
 * Regenerate with UPDATE_CONTRACT=1, then read the diff before committing:
 *
 *     task wt:test:api -- --testsuite Contract   # see what moved
 *     UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract
 *
 * The directory sits under api/ rather than at the repository root because
 * `task wt:test:api` mounts api/ alone; a root-level directory would be
 * invisible to the very suite that maintains it.
 */
trait ContractSnapshotTrait
{
    protected static function contractDir(): string
    {
        // __DIR__ is api/tests/Contract.
        return \dirname(__DIR__, 2).'/contract';
    }

    protected static function contractPath(string $relativePath): string
    {
        return self::contractDir().'/'.$relativePath;
    }

    protected static function updatingContract(): bool
    {
        return ($_SERVER['UPDATE_CONTRACT'] ?? $_ENV['UPDATE_CONTRACT'] ?? '') === '1';
    }

    /**
     * @param array<mixed> $actual
     */
    protected function assertMatchesContract(string $relativePath, array $actual, string $rationale): void
    {
        $path = self::contractPath($relativePath);
        $encoded = self::encode($actual);

        if (self::updatingContract()) {
            $dir = \dirname($path);
            if (!is_dir($dir) && !mkdir($dir, 0o775, true) && !is_dir($dir)) {
                self::fail(sprintf('Could not create the contract directory "%s".', $dir));
            }
            file_put_contents($path, $encoded);

            self::assertTrue(true, 'Contract regenerated.');

            return;
        }

        if (!is_file($path)) {
            self::fail(sprintf(
                "The contract file \"contract/%s\" is missing.\n%s\nRegenerate it with:\n    UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract",
                $relativePath,
                $rationale,
            ));
        }

        $expected = (string) file_get_contents($path);

        if ($expected === $encoded) {
            self::assertTrue(true);

            return;
        }

        self::assertSame($expected, $encoded, sprintf(
            "contract/%s is out of date.\n\n%s\n\nIf the change is intended, regenerate the file and commit it so the diff is reviewed:\n    UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract",
            $relativePath,
            $rationale,
        ));
    }

    /**
     * Pretty-printed and trailing-newline-terminated, so `git diff` on a
     * contract file reads as a list of changed lines rather than one very long
     * one.
     *
     * @param array<mixed> $data
     */
    private static function encode(array $data): string
    {
        return json_encode(
            $data,
            \JSON_PRETTY_PRINT | \JSON_UNESCAPED_SLASHES | \JSON_UNESCAPED_UNICODE | \JSON_THROW_ON_ERROR,
        )."\n";
    }
}
