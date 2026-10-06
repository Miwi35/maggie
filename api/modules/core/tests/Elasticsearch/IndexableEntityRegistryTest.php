<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Mapping\ClassMetadata;
use Doctrine\ORM\Mapping\ClassMetadataFactory;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Uid\Ulid;

#[Indexed(index: 'registry_things', module: 'test')]
class StubRegistryParent implements IndexableInterface
{
    public function getId(): Ulid
    {
        return new Ulid();
    }

    public function toSearchDocument(): array
    {
        return [];
    }
}

class StubRegistryChild extends StubRegistryParent
{
}

final class IndexableEntityRegistryTest extends TestCase
{
    /**
     * @param list<class-string> $classes
     *
     * @return array<string, class-string>
     */
    private function discover(array $classes): array
    {
        $factory = $this->createStub(ClassMetadataFactory::class);
        $factory->method('getAllMetadata')->willReturn(array_map(
            static function (string $class): ClassMetadata {
                $meta = new \ReflectionClass(ClassMetadata::class)->newInstanceWithoutConstructor();
                $meta->name = $class;

                return $meta;
            },
            $classes,
        ));
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getMetadataFactory')->willReturn($factory);

        return (new IndexableEntityRegistry($em, new IndexMetadataReader()))->getAll();
    }

    /** A child sharing its parent's index must not take the index over: reindexing would then query the child's rows only (MAG-182). */
    public function testAChildSharingItsParentsIndexDoesNotReplaceTheParent(): void
    {
        $parentFirst = $this->discover([StubRegistryParent::class, StubRegistryChild::class]);
        $childFirst = $this->discover([StubRegistryChild::class, StubRegistryParent::class]);

        self::assertSame(['registry_things' => StubRegistryParent::class], $parentFirst);
        self::assertSame(['registry_things' => StubRegistryParent::class], $childFirst);
    }
}
