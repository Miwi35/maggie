<?php

namespace Maggie\Finance\Tests\Entity;

use Maggie\Finance\Entity\Project;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Uid\Ulid;

/**
 * setDependsOn() keeps the very instances it is given (MAG-519): a PATCH puts
 * unmanaged copies built from Elasticsearch in the collection, and the managed
 * ones the handler passes must replace them, or Doctrine refuses the flush.
 */
class ProjectDependsOnTest extends TestCase
{
    private static function project(string $name, ?Ulid $id = null): Project
    {
        $project = (new Project())->setName($name);
        if (null !== $id) {
            $property = new \ReflectionProperty(Project::class, 'id');
            $property->setValue($project, $id);
        }

        return $project;
    }

    public function testADifferentInstanceWithTheSameIdIsSwappedIn(): void
    {
        $managed = self::project('Déménagement');
        $copy = self::project('Déménagement', $managed->getId());
        $sofa = self::project('Canapé');
        $sofa->getDependsOn()->add($copy);

        $sofa->setDependsOn([$managed]);

        self::assertCount(1, $sofa->getDependsOn());
        self::assertSame($managed, $sofa->getDependsOn()->first());
    }

    public function testTheSameInstanceIsKeptAndTheUnwantedOnesRemoved(): void
    {
        $move = self::project('Déménagement');
        $japan = self::project('Voyage au Japon');
        $sofa = self::project('Canapé');
        $sofa->setDependsOn([$move, $japan]);

        $sofa->setDependsOn([$move]);

        self::assertSame([$move], array_values($sofa->getDependsOn()->toArray()));
    }
}
