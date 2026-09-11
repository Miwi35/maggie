<?php

namespace Maggie\Core\Tests\Mercure;

use Maggie\Core\Mercure\ChangesetStore;
use PHPUnit\Framework\TestCase;

class ChangesetStoreTest extends TestCase
{
    public function testCaptureAndGet(): void
    {
        $store = new ChangesetStore();
        $entity = new \stdClass();

        $store->capture($entity, ['name', 'email']);

        self::assertSame(['name', 'email'], $store->get($entity));
    }

    public function testGetReturnsNullForUntracked(): void
    {
        $store = new ChangesetStore();
        $entity = new \stdClass();

        self::assertNull($store->get($entity));
    }

    public function testCaptureMergesDuplicates(): void
    {
        $store = new ChangesetStore();
        $entity = new \stdClass();

        $store->capture($entity, ['name']);
        $store->capture($entity, ['name', 'email']);

        $result = $store->get($entity);
        self::assertCount(2, $result);
        self::assertContains('name', $result);
        self::assertContains('email', $result);
    }

    public function testDifferentEntitiesTrackedSeparately(): void
    {
        $store = new ChangesetStore();
        $a = new \stdClass();
        $b = new \stdClass();

        $store->capture($a, ['name']);
        $store->capture($b, ['email']);

        self::assertSame(['name'], $store->get($a));
        self::assertSame(['email'], $store->get($b));
    }
}
