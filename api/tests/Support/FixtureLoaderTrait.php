<?php

namespace App\Tests\Support;

use Doctrine\Common\DataFixtures\Purger\ORMPurger;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Finance\Doctrine\TransactionListener;
use Nelmio\Alice\Loader\NativeLoader;

trait FixtureLoaderTrait
{
    private array $fixtures = [];

    protected function loadFixtures(string ...$files): void
    {
        $this->purgeDatabase();

        $loader = new NativeLoader();
        $fixturesDir = dirname((new \ReflectionClass(static::class))->getFileName()).'/fixtures/';

        $this->fixtures = [];
        foreach ($files as $file) {
            // An absolute path is taken as given, so a fixture describing a
            // world two suites care about can be shared instead of copied —
            // two copies of the same world drift, and then the two suites
            // disagree about what they are testing.
            $path = str_starts_with($file, '/') ? $file : $fixturesDir.$file;

            $objectSet = $loader->loadFile($path, [], $this->fixtures);
            $this->fixtures = array_merge($this->fixtures, $objectSet->getObjects());
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        foreach ($this->fixtures as $object) {
            $em->persist($object);
        }

        // Fixtures are history already stored, not movements being recorded.
        $this->flushWithoutTransactionEffects($em);
    }

    /**
     * Flushes as stored history: categorization, transfer and rejection
     * detection do not run on what this flush writes. For the tests of those
     * very passes, which need unpaired lines to start from.
     */
    protected function flushWithoutTransactionEffects(EntityManagerInterface $em): void
    {
        $effects = self::getContainer()->get(TransactionListener::class);
        $events = $em->getEventManager();
        // Listeners are lazy services: they only become removable once resolved.
        $events->getAllListeners();
        $events->removeEventListener([Events::preFlush, Events::onFlush, Events::postFlush], $effects);
        try {
            $em->flush();
        } finally {
            $events->addEventListener([Events::preFlush, Events::onFlush, Events::postFlush], $effects);
        }
    }

    protected function purgeDatabase(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $purger = new ORMPurger($em);
        $purger->purge();
        $em->clear();
    }

    protected function getFixture(string $ref): object
    {
        if (!isset($this->fixtures[$ref])) {
            throw new \InvalidArgumentException(sprintf('Fixture "%s" not found. Available: %s', $ref, implode(', ', array_keys($this->fixtures))));
        }

        return $this->fixtures[$ref];
    }
}
