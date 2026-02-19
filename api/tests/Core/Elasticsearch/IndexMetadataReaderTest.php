<?php

namespace App\Tests\Core\Elasticsearch;

use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Uid\Ulid;

#[Indexed(index: 'test_entities', module: 'test')]
class StubIndexedEntity
{
    private Ulid $id;

    #[IndexedField(type: 'text', boost: 3.0)]
    private string $title;

    #[IndexedField(type: 'keyword')]
    private string $status;

    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $createdAt;

    #[IndexedField(type: 'text', keyword: true)]
    private string $location;

    #[IndexedRelation(targetEntity: 'App\Entity\User', sourceField: 'userId')]
    private object $user;

    private string $notIndexed;
}

class StubNotIndexedEntity
{
    private string $name;
}

class IndexMetadataReaderTest extends TestCase
{
    private IndexMetadataReader $reader;

    protected function setUp(): void
    {
        $this->reader = new IndexMetadataReader();
    }

    public function testReadReturnsNullForNonIndexedEntity(): void
    {
        $result = $this->reader->read(StubNotIndexedEntity::class);
        self::assertNull($result);
    }

    public function testReadReturnsIndexMetadata(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);

        self::assertNotNull($result);
        self::assertSame('test_entities', $result['index']);
        self::assertSame('test', $result['module']);
    }

    public function testReadParsesFieldAttributes(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $fields = $result['fields'];

        self::assertArrayHasKey('title', $fields);
        self::assertSame('text', $fields['title']['type']);
        self::assertArrayNotHasKey('boost', $fields['title']); // boost is query-time only

        // Boost stored separately
        self::assertArrayHasKey('title', $result['boosts']);
        self::assertSame(3.0, $result['boosts']['title']);

        self::assertArrayHasKey('status', $fields);
        self::assertSame('keyword', $fields['status']['type']);

        self::assertArrayHasKey('createdAt', $fields);
        self::assertSame('date', $fields['createdAt']['type']);
    }

    public function testReadParsesKeywordSubField(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $fields = $result['fields'];

        self::assertArrayHasKey('location', $fields);
        self::assertSame('text', $fields['location']['type']);
        self::assertArrayHasKey('fields', $fields['location']);
        self::assertSame('keyword', $fields['location']['fields']['keyword']['type']);
    }

    public function testReadParsesRelations(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $relations = $result['relations'];

        self::assertArrayHasKey('user', $relations);
        self::assertSame('App\Entity\User', $relations['user']['targetEntity']);
        self::assertSame('userId', $relations['user']['sourceField']);
    }

    public function testNonIndexedFieldsAreExcluded(): void
    {
        $result = $this->reader->read(StubIndexedEntity::class);
        $fields = $result['fields'];

        self::assertArrayNotHasKey('notIndexed', $fields);
        self::assertArrayNotHasKey('id', $fields);
    }

    public function testGetIndexNameReturnsName(): void
    {
        self::assertSame('test_entities', $this->reader->getIndexName(StubIndexedEntity::class));
    }

    public function testGetIndexNameReturnsNullForNonIndexed(): void
    {
        self::assertNull($this->reader->getIndexName(StubNotIndexedEntity::class));
    }
}
