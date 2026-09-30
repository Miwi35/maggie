<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure;

/**
 * The one place that knows how a Mercure topic is spelled.
 *
 * Four of the repository's seven real-time regressions were a string that did
 * not match another string: b333376 published an absolute, host-dependent IRI;
 * 8380178 published outside the user's scope during a Google sync; c2d3758
 * published the same update twice, once scoped and once not; e9c17b9 published
 * one topic while the client subscribed to another.
 *
 * None of them was a hard problem. They happened because the convention lived
 * in a sprintf in the publishing middleware, in a template literal in the
 * admin's useMercure hook, and in a string in each mobile ViewModel — three
 * copies of one rule, and nothing comparing them. This class is the copy the
 * API publishes from, and MercureTopicContractTest writes it out to
 * contract/mercure-topics.json so the other two can be checked against it.
 *
 * The convention:
 *
 *     collection   /api/tasks
 *     item          /api/tasks/{id}
 *     subscribed   /users/{userId}/api/tasks/{id}
 *
 * Relative, always — an absolute IRI carries the host that generated it,
 * which is not the host the client subscribed from.
 */
final class MercureTopic
{
    /**
     * The collection topic for an entity: /api/grocery_lists.
     *
     * @param class-string|object $entity
     */
    public static function collection(string|object $entity): string
    {
        $shortName = (new \ReflectionClass($entity))->getShortName();

        return self::collectionFromShortName($shortName);
    }

    public static function collectionFromShortName(string $shortName): string
    {
        // GroceryList → grocery_list
        $snake = strtolower((string) preg_replace('/(?<!^)[A-Z]/', '_$0', $shortName));

        return '/api/'.self::pluralize($snake);
    }

    /**
     * The item topic: /api/tasks/{id}. Relative on purpose — see the class
     * docblock.
     */
    public static function item(string $collectionTopic, string $id): string
    {
        return $collectionTopic.'/'.$id;
    }

    /**
     * What is actually published and subscribed:
     * /users/{userId}/api/tasks/{id}.
     *
     * Every update is scoped to the user who owns the entity, never to the
     * user who happened to trigger it — a Google sync runs for one user and
     * must not publish into another's stream (8380178).
     */
    public static function scoped(string $userId, string $topic): string
    {
        return '/users/'.$userId.$topic;
    }

    /**
     * The pattern a client subscribes to, with the id left as a placeholder:
     * /users/{userId}/api/tasks/{id}. This is the string published to
     * contract/mercure-topics.json, and the one the admin and mobile suites
     * check themselves against.
     */
    public static function subscriptionPattern(string $collectionTopic): string
    {
        return '/users/{userId}'.$collectionTopic.'/{id}';
    }

    /**
     * Pluralize a snake_case entity name to match API Platform's collection
     * route. Handles the consonant+"y" → "ies" case (e.g. category →
     * categories); every other entity keeps the simple "+s" form.
     */
    private static function pluralize(string $snake): string
    {
        if (preg_match('/[bcdfghjklmnpqrstvwxz]y$/', $snake)) {
            return substr($snake, 0, -1).'ies';
        }

        return $snake.'s';
    }
}
