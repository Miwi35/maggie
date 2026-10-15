<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\OpenApi\Factory\OpenApiFactoryInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\QualificationKind;
use Mcp\Capability\RegistryInterface;
use Mcp\Schema\Tool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * A parameter of `Qualification` is one field and its migration (MAG-476):
 * from there it must exist on every entity that embeds the object, be copied
 * by `Transaction::qualify()`, and reach every channel — the columns, the
 * Mercure payloads, the Elasticsearch documents and mapping, the OpenAPI
 * schemas and the arguments of the MCP tools that qualify.
 *
 * Nothing in PHP breaks when one of them forgets the new field: the client
 * simply never sees it. Each test names the place it is missing from.
 */
final class QualificationContractTest extends KernelTestCase
{
    /** The entities that embed the qualification, by their OpenAPI schema name. */
    private const ENTITIES = [
        'Transaction' => Transaction::class,
        'RecurringOperation' => RecurringOperation::class,
    ];

    /** Every resource that serves them: an operation is served as series, rule and operation (MAG-478). */
    private const SCHEMAS = ['Transaction', 'RecurringOperation', 'CategorizationRule', 'Operation'];

    /** The MCP tools that read and write it. */
    private const TOOLS = ['manage_transactions', 'manage_operations'];

    public function testTheQualificationHasParameters(): void
    {
        self::assertSame(['mandatory', 'kind'], Qualification::fields(), 'A field was added to Qualification: this test then checks that it is exposed everywhere. Update this list once it is.');
    }

    /** @param class-string $entity */
    #[DataProvider('entities')]
    public function testEveryEntityMapsEveryParameter(string $entity): void
    {
        $metadata = self::em()->getClassMetadata($entity);

        self::assertSame(Qualification::class, $metadata->embeddedClasses['qualification']->class ?? null, sprintf('%s does not embed Qualification as `qualification`.', $entity));
        foreach (Qualification::fields() as $field) {
            self::assertTrue($metadata->hasField('qualification.'.$field), sprintf('%s has no column for qualification.%s: add it to the migration.', $entity, $field));
        }
    }

    /** @param class-string<MercurePublishable&IndexableInterface> $entity */
    #[DataProvider('entities')]
    public function testEveryParameterIsPublishedAndIndexed(string $entity): void
    {
        $instance = self::qualified($entity);
        $expected = self::distinctQualification()->toArray();

        self::assertSame($expected, $instance->toMercurePayload()['qualification'] ?? null, sprintf('The Mercure payload of %s does not carry the whole qualification.', $entity));
        self::assertSame($expected, $instance->toSearchDocument()['qualification'] ?? null, sprintf('The search document of %s does not carry the whole qualification.', $entity));

        $mapping = (new IndexMetadataReader())->getMapping($entity);
        self::assertSame(Qualification::fields(), array_keys($mapping['qualification']['properties'] ?? []), sprintf('The Elasticsearch mapping of %s misses a parameter: Qualification::SEARCH_MAPPING.', $entity));

        $changed = $instance->toMercurePayload(['qualification.'.Qualification::fields()[0]]);
        self::assertSame(['qualification' => $expected], $changed, sprintf('A differential update of %s does not publish the qualification whole.', $entity));
    }

    public function testEveryParameterSurvivesTheWayBackFromElasticsearch(): void
    {
        self::assertTrue(self::distinctQualification()->equals(Qualification::fromSearchDocument(self::distinctQualification()->toArray())));
    }

    public function testQualifyCopiesEveryParameter(): void
    {
        $category = new Category();
        $transaction = (new Transaction())->qualify(self::distinctQualification()->withCategory($category), null);

        self::assertTrue($transaction->getQualification()->equals(self::distinctQualification()->withCategory($category)));
    }

    public function testEveryOpenApiSchemaOffersEveryParameter(): void
    {
        $document = self::getContainer()->get('serializer')->normalize(self::getContainer()->get(OpenApiFactoryInterface::class)(), 'json');
        self::assertIsArray($document);
        $schemas = $document['components']['schemas'];

        foreach (self::SCHEMAS as $resource) {
            foreach ([$resource, $resource.'.jsonld', $resource.'.jsonMergePatch'] as $name) {
                if (!isset($schemas[$name])) {
                    continue;
                }
                $qualification = self::property($schemas, $schemas[$name], 'qualification');
                self::assertNotNull($qualification, sprintf('The OpenAPI schema %s has no qualification.', $name));
                foreach (Qualification::fields() as $field) {
                    self::assertNotNull(self::property($schemas, $qualification, $field), sprintf('The OpenAPI schema %s does not offer qualification.%s.', $name, $field));
                }
            }
        }
    }

    public function testEveryToolThatQualifiesTakesEveryParameter(): void
    {
        $container = self::getContainer();
        $container->get('mcp.server');
        $registry = $container->get('mcp.registry');
        self::assertInstanceOf(RegistryInterface::class, $registry);

        $arguments = [];
        foreach ($registry->getTools()->references as $reference) {
            $tool = $reference instanceof Tool ? $reference : $reference->tool;
            $arguments[$tool->name] = array_keys((array) ($tool->inputSchema['properties'] ?? []));
        }

        foreach (self::TOOLS as $name) {
            foreach (Qualification::fields() as $field) {
                self::assertContains($field, $arguments[$name] ?? [], sprintf('The MCP tool %s does not take %s: add the argument and pass it to QualificationArguments::whole().', $name, $field));
            }
        }
    }

    /** @return iterable<string, array{class-string}> */
    public static function entities(): iterable
    {
        foreach (self::ENTITIES as $name => $class) {
            yield $name => [$class];
        }
    }

    /** Every parameter away from its default, so a field left behind shows. */
    private static function distinctQualification(): Qualification
    {
        return new Qualification(true, QualificationKind::Repayment);
    }

    /**
     * @param class-string<MercurePublishable&IndexableInterface> $entity
     *
     * @return MercurePublishable&IndexableInterface
     */
    private static function qualified(string $entity): object
    {
        $user = new User();
        $category = (new Category())->setName('Crédits')->setUser($user);
        $account = (new Account())->setName('Courant')->setUser($user);
        $qualification = self::distinctQualification()->withCategory($category);

        return match ($entity) {
            Transaction::class => (new Transaction())->setUser($user)->setAccount($account)->setLabel('PRLV')->qualify($qualification, null),
            RecurringOperation::class => (new RecurringOperation())->setUser($user)->setAccount($account)->setLabel('Crédit auto')
                ->setAnchorOn(new \DateTimeImmutable('2027-01-05'))->setReferenceAmountCents(-25000)->requalify($qualification),
            default => throw new \LogicException($entity),
        };
    }

    /**
     * A property of an OpenAPI schema, through its `$ref`, `allOf`, `anyOf`
     * and `oneOf`.
     *
     * @param array<string, mixed> $schemas
     * @param array<string, mixed> $schema
     *
     * @return array<string, mixed>|null
     */
    private static function property(array $schemas, array $schema, string $name): ?array
    {
        if (isset($schema['$ref'])) {
            return self::property($schemas, $schemas[substr($schema['$ref'], \strlen('#/components/schemas/'))] ?? [], $name);
        }
        if (isset($schema['properties'][$name])) {
            return $schema['properties'][$name];
        }
        foreach (['allOf', 'anyOf', 'oneOf'] as $combinator) {
            foreach ($schema[$combinator] ?? [] as $part) {
                $found = self::property($schemas, $part, $name);
                if (null !== $found) {
                    return $found;
                }
            }
        }

        return null;
    }

    private static function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
