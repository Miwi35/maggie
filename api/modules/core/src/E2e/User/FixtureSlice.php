<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\User;

use Symfony\Component\Yaml\Yaml;

/**
 * Cuts the part of the e2e fixture files that belongs to some users, so a journey
 * user gets the seed's data through the seed's own files instead of a copy.
 *
 * An entry belongs to the users when it refers to them, directly or through
 * another entry that does (a recipe ingredient belongs to its recipe's owner),
 * plus whatever those entries need to exist. A user outside the slice reached
 * that way is a fixture that ties two accounts together: it fails here, loudly,
 * rather than creating an account nobody asked for.
 */
final class FixtureSlice
{
    /**
     * @param list<string>            $files       fixture files, in load order
     * @param list<string>            $roots       references of the users to keep
     * @param list<class-string>|null $onlyClasses keep only these classes (and what they need); null keeps everything that belongs
     *
     * @return array<class-string, array<string, mixed>> Alice's own format
     */
    public static function of(array $files, array $roots, ?array $onlyClasses = null): array
    {
        /** @var array<class-string, array<string, mixed>> $merged */
        $merged = [];
        /** @var array<string, class-string> $classOf reference (range expanded) → class */
        $classOf = [];
        /** @var array<string, string> $keyOf reference → the key it was declared under */
        $keyOf = [];
        /** @var array<string, list<string>> $needs declared key → references it names */
        $needs = [];

        foreach ($files as $file) {
            $parsed = Yaml::parseFile($file);
            foreach (\is_array($parsed) ? $parsed : [] as $class => $entries) {
                foreach ($entries as $key => $properties) {
                    $merged[$class][$key] = $properties;
                    foreach (self::namesOf($key) as $name) {
                        $classOf[$name] = $class;
                        $keyOf[$name] = $key;
                    }
                }
            }
        }

        foreach ($merged as $entries) {
            foreach ($entries as $key => $properties) {
                $needs[$key] = array_values(array_unique(array_filter(
                    self::referencesIn($properties),
                    static fn (string $name): bool => isset($keyOf[$name]),
                )));
            }
        }

        foreach ($roots as $root) {
            if (!isset($keyOf[$root])) {
                throw new \RuntimeException(sprintf('The e2e fixtures declare no "%s".', $root));
            }
        }

        // Downwards: whatever refers to a kept entry belongs with it.
        $kept = array_fill_keys(array_map(static fn (string $root): string => $keyOf[$root], $roots), true);
        do {
            $grew = false;
            foreach ($needs as $key => $names) {
                if (isset($kept[$key])) {
                    continue;
                }
                foreach ($names as $name) {
                    if (isset($kept[$keyOf[$name]])) {
                        $kept[$key] = true;
                        $grew = true;
                        break;
                    }
                }
            }
        } while ($grew);

        if (null !== $onlyClasses) {
            foreach ($kept as $key => $_) {
                if (!\in_array(self::classOfKey($merged, $key), $onlyClasses, true)) {
                    unset($kept[$key]);
                }
            }
        }

        // Upwards: what the kept entries need.
        $queue = array_keys($kept);
        while ([] !== $queue) {
            $key = array_pop($queue);
            foreach ($needs[$key] as $name) {
                $dependency = $keyOf[$name];
                if (!isset($kept[$dependency])) {
                    $kept[$dependency] = true;
                    $queue[] = $dependency;
                }
            }
        }

        $slice = [];
        foreach ($merged as $class => $entries) {
            foreach ($entries as $key => $properties) {
                if (isset($kept[$key])) {
                    $slice[$class][$key] = $properties;
                }
            }
        }

        self::assertNoStranger($slice, $roots, $keyOf);

        return $slice;
    }

    /** @param array<class-string, array<string, mixed>> $slice */
    public static function dump(array $slice): string
    {
        return Yaml::dump($slice, 6, 2, Yaml::DUMP_MULTI_LINE_LITERAL_BLOCK);
    }

    /**
     * @param array<class-string, array<string, mixed>> $slice
     * @param list<string>                              $roots
     * @param array<string, string>                     $keyOf
     */
    private static function assertNoStranger(array $slice, array $roots, array $keyOf): void
    {
        $allowed = array_map(static fn (string $root): string => $keyOf[$root], $roots);

        foreach ($slice as $class => $entries) {
            if (!str_ends_with($class, '\\Entity\\User')) {
                continue;
            }
            foreach (array_keys($entries) as $key) {
                if (!\in_array($key, $allowed, true)) {
                    throw new \RuntimeException(sprintf('The e2e fixtures tie "%s" to the users of this slice: it cannot be left out.', $key));
                }
            }
        }
    }

    /** @param array<class-string, array<string, mixed>> $merged */
    private static function classOfKey(array $merged, string $key): string
    {
        foreach ($merged as $class => $entries) {
            if (isset($entries[$key])) {
                return $class;
            }
        }

        throw new \LogicException(sprintf('No class declares "%s".', $key));
    }

    /**
     * `name_{1..3}` declares name_1, name_2 and name_3.
     *
     * @return list<string>
     */
    private static function namesOf(string $key): array
    {
        if (1 !== preg_match('/^(.*)\{(\d+)\.\.(\d+)\}(.*)$/', $key, $match)) {
            return [$key];
        }

        $names = [];
        for ($i = (int) $match[2]; $i <= (int) $match[3]; ++$i) {
            $names[] = $match[1].$i.$match[4];
        }

        return $names;
    }

    /** @return list<string> */
    private static function referencesIn(mixed $value): array
    {
        if (\is_string($value)) {
            preg_match_all('/(?<![\w.])@([A-Za-z_][A-Za-z0-9_]*)/', $value, $matches);

            return $matches[1];
        }

        $found = [];
        if (\is_array($value)) {
            foreach ($value as $item) {
                array_push($found, ...self::referencesIn($item));
            }
        }

        return $found;
    }
}
