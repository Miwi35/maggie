<?php

namespace App\Tests\Support;

use Doctrine\Common\DataFixtures\Purger\ORMPurger;
use Nelmio\Alice\Loader\NativeLoader;

trait FixtureLoaderTrait
{
    private array $fixtures = [];

    protected function loadFixtures(string ...$files): void
    {
        $this->purgeDatabase();

        $loader = new NativeLoader();
        $fixturesDir = dirname((new \ReflectionClass(static::class))->getFileName()) . '/fixtures/';

        $this->fixtures = [];
        foreach ($files as $file) {
            // An absolute path is taken as given, so a fixture describing a
            // world two suites care about can be shared instead of copied —
            // two copies of the same world drift, and then the two suites
            // disagree about what they are testing.
            $path = str_starts_with($file, '/') ? $file : $fixturesDir . $file;

            $objectSet = $loader->loadFile($path, [], $this->fixtures);
            $this->fixtures = array_merge($this->fixtures, $objectSet->getObjects());
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        foreach ($this->fixtures as $object) {
            $em->persist($object);
        }
        $em->flush();
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
            throw new \InvalidArgumentException(sprintf(
                'Fixture "%s" not found. Available: %s',
                $ref,
                implode(', ', array_keys($this->fixtures)),
            ));
        }

        return $this->fixtures[$ref];
    }
}
