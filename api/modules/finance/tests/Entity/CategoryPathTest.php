<?php

namespace Maggie\Finance\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Uid\Ulid;

/** The depth and the path of a category on the channels a client reads them from. */
class CategoryPathTest extends TestCase
{
    private Category $leisure;
    private Category $bars;
    private Category $obsydian;

    protected function setUp(): void
    {
        $user = (new User())->setEmail('chemin@example.com')->setGoogleId('google-chemin')->setName('Chemin');

        $this->leisure = (new Category())->setName('Loisirs')->setUser($user);
        $this->bars = (new Category())->setName('Bars')->setParent($this->leisure)->setUser($user);
        $this->obsydian = (new Category())->setName('Obsydian')->setParent($this->bars)->setUser($user);
    }

    public function testTheDepthAndThePathFollowTheParentChain(): void
    {
        self::assertSame([1, 2, 3], [$this->leisure->getDepth(), $this->bars->getDepth(), $this->obsydian->getDepth()]);
        self::assertSame('Loisirs', $this->leisure->getPath());
        self::assertSame('Loisirs › Bars › Obsydian', $this->obsydian->getPath());
    }

    public function testTheSearchDocumentAndTheMercurePayloadSpellThemTheSame(): void
    {
        $document = $this->obsydian->toSearchDocument();
        $payload = $this->obsydian->toMercurePayload();

        self::assertSame(3, $document['depth']);
        self::assertSame('Loisirs › Bars › Obsydian', $document['path']);
        self::assertSame($document['depth'], $payload['depth']);
        self::assertSame($document['path'], $payload['path']);
    }

    public function testADifferentialUpdateThatRenamesCarriesThePath(): void
    {
        $this->bars->setName('Pubs');

        self::assertSame(['name', 'depth', 'path'], array_keys($this->bars->toMercurePayload(['name'])));
        self::assertSame(['color'], array_keys($this->bars->setColor('#112233')->toMercurePayload(['color'])));
    }

    public function testACategoryRebuiltFromItsDocumentReadsTheIndexedDepthAndPathAndKeepsItsParent(): void
    {
        $parentId = new Ulid();
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getReference')->willReturn($this->bars);

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate([
            'id' => (string) new Ulid(),
            'name' => 'Obsydian',
            'obligation' => 'optional',
            'parentId' => (string) $parentId,
            'depth' => 3,
            'path' => 'Loisirs › Bars › Obsydian',
        ], Category::class);

        self::assertInstanceOf(Category::class, $hydrated);
        self::assertSame($this->bars, $hydrated->getParent());
        self::assertSame(3, $hydrated->getDepth());
        self::assertSame('Loisirs › Bars › Obsydian', $hydrated->getPath());
    }

    public function testACategoryIndexedBeforeTheFieldsExistedFallsBackOnItsParents(): void
    {
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getReference')->willReturn($this->bars);

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate([
            'id' => (string) new Ulid(),
            'name' => 'Obsydian',
            'obligation' => 'optional',
            'parentId' => (string) new Ulid(),
        ], Category::class);

        self::assertSame(3, $hydrated->getDepth());
        self::assertSame('Loisirs › Bars › Obsydian', $hydrated->getPath());
    }
}
