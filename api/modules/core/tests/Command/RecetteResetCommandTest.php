<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Service\RecetteAccount;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

final class RecetteResetCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;

    /** @var list<array{method: string, url: string, headers: array<string, mixed>, body: array<string, mixed>}> */
    private array $agentRequests = [];

    /** @var callable(): ResponseInterface */
    private $agentAnswer;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->agentRequests = [];
        $this->agentAnswer = static fn () => new MockResponse(
            json_encode(['deleted' => ['conversations' => 3, 'messages' => 12]], \JSON_THROW_ON_ERROR),
            ['response_headers' => ['content-type: application/json']],
        );
        self::getContainer()->set('http_client', new MockHttpClient(function (string $method, string $url, array $options): ResponseInterface {
            $this->agentRequests[] = [
                'method' => $method,
                'url' => $url,
                'headers' => $options['normalized_headers'] ?? [],
                'body' => json_decode($options['body'] ?? '{}', true, 512, \JSON_THROW_ON_ERROR),
            ];

            return ($this->agentAnswer)();
        }));
        $this->loadFixtures('RecetteResetCommandTest.yaml');
        $this->resetAsyncTransport();
    }

    public function testItWipesEveryModuleOfTheRecetteAccountAndKeepsTheAccountAndItsRole(): void
    {
        $tester = $this->execute([]);

        $tester->assertCommandIsSuccessful();
        foreach ($this->fixtures as $name => $object) {
            if (str_ends_with($name, '_recette') && !$object instanceof User) {
                self::assertNull($this->em()->getRepository($object::class)->find($object->getId()), "$name must be gone");
            }
        }
        // The owner keeps one of each; a meal and a plain event are both events, an ingredient and a product both products.
        foreach ([Agenda::class => 1, Event::class => 2, Meal::class => 1, Product::class => 2, GroceryItem::class => 1] as $class => $expected) {
            self::assertSame($expected, $this->em()->getRepository($class)->count([]), "$class: only the owner's rows are left");
        }
        $recette = $this->em()->getRepository(User::class)->findOneBy(['email' => RecetteAccount::EMAIL]);
        self::assertNotNull($recette);
        self::assertContains(User::ROLE_PROACTION_TRIGGER, $recette->getRoles());
    }

    public function testTheOtherAccountComesOutIdenticalInEveryEntityClass(): void
    {
        $before = $this->snapshot();
        $recetteIds = [];
        foreach ($this->fixtures as $name => $object) {
            if (str_ends_with($name, '_recette') && !$object instanceof User) {
                $recetteIds[] = (string) $object->getId();
            }
        }

        $this->execute([])->assertCommandIsSuccessful();

        $expected = array_map(static fn (array $ids) => array_values(array_diff($ids, $recetteIds)), $before);
        self::assertSame($expected, $this->snapshot());
        self::assertNotEmpty($recetteIds);
    }

    public function testItDeletesTheElasticsearchDocumentsOfTheRecetteRowsOnly(): void
    {
        $this->execute([])->assertCommandIsSuccessful();

        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[$message->indexName][] = $message->documentId;
            }
        }

        self::assertArrayNotHasKey('users', $deleted);
        foreach (['agendas', 'events', 'meals', 'grocery_lists', 'products', 'recipes', 'transactions', 'notifications'] as $index) {
            self::assertArrayHasKey($index, $deleted, $index);
        }
        // The meal is an event: it is dropped from both indices.
        self::assertContains((string) $this->getFixture('meal_recette')->getId(), $deleted['events']);
        self::assertContains((string) $this->getFixture('meal_recette')->getId(), $deleted['meals']);
        self::assertContains((string) $this->getFixture('ingredient_recette')->getId(), $deleted['products']);

        $others = [];
        foreach ($this->fixtures as $name => $object) {
            if (str_ends_with($name, '_other')) {
                $others[] = (string) $object->getId();
            }
        }
        self::assertSame([], array_intersect($others, array_merge(...array_values($deleted))));
    }

    public function testItAsksTheAgentToWipeTheRecetteUserOnly(): void
    {
        $tester = $this->execute([]);

        self::assertCount(1, $this->agentRequests);
        $request = $this->agentRequests[0];
        self::assertSame('POST', $request['method']);
        self::assertStringEndsWith('/internal/recette/reset', $request['url']);
        self::assertContains('Authorization: Bearer '.$_SERVER['SERVICE_TOKEN'], $request['headers']['authorization']);
        self::assertSame(
            ['userId' => (string) $this->getFixture('user_recette')->getId(), 'dryRun' => false],
            $request['body'],
        );
        self::assertStringContainsString('conversations', $tester->getDisplay());
    }

    public function testDryRunReportsAndDeletesNothingOnEitherSide(): void
    {
        $before = $this->snapshot();

        $tester = $this->execute(['--dry-run' => true]);

        $tester->assertCommandIsSuccessful();
        self::assertSame($before, $this->snapshot());
        self::assertTrue($this->agentRequests[0]['body']['dryRun']);
        self::assertStringContainsString('would be deleted', $tester->getDisplay());
        self::assertStringContainsString('Meal', $tester->getDisplay());
        self::assertSame([], array_filter(
            $this->getAsyncTransport()->getSent(),
            static fn ($envelope) => $envelope->getMessage() instanceof DeleteDocumentCommand,
        ));
    }

    public function testAnAgentFailureLeavesTheApiDataUntouched(): void
    {
        $this->agentAnswer = static fn () => new MockResponse('boom', ['http_code' => 500]);
        $before = $this->snapshot();

        $tester = $this->execute([]);

        self::assertSame(Command::FAILURE, $tester->getStatusCode());
        self::assertStringContainsString('nothing deleted', $tester->getDisplay());
        self::assertSame($before, $this->snapshot());
    }

    public function testAnUnreachableAgentLeavesTheApiDataUntouched(): void
    {
        $this->agentAnswer = static fn () => new MockResponse('', ['error' => 'connection refused']);
        $before = $this->snapshot();

        self::assertSame(Command::FAILURE, $this->execute([])->getStatusCode());
        self::assertSame($before, $this->snapshot());
    }

    public function testItCanOnlyTargetTheRecetteAccount(): void
    {
        $definition = (new Application(self::$kernel))->find('app:recette:reset')->getDefinition();

        self::assertSame([], $definition->getArguments(), 'no argument can name a user');
        self::assertSame(['dry-run'], array_keys($definition->getOptions()), 'no option can name a user');
    }

    public function testWithoutTheRecetteAccountNothingIsResetAndEveryoneElseIsUntouched(): void
    {
        $recette = $this->em()->getRepository(User::class)->findOneBy(['email' => RecetteAccount::EMAIL]);
        $recette->setEmail('someone-else@example.com');
        $this->em()->flush();
        $this->em()->clear();
        $before = $this->snapshot();

        $tester = $this->execute([]);

        $tester->assertCommandIsSuccessful();
        self::assertStringContainsString('nothing to reset', $tester->getDisplay());
        self::assertSame([], $this->agentRequests);
        self::assertSame($before, $this->snapshot());
        self::assertNotSame([], $before['Maggie\Calendar\Entity\Agenda']);
    }

    /** @param array<string, mixed> $input */
    private function execute(array $input): CommandTester
    {
        $this->em()->clear();
        $tester = new CommandTester((new Application(self::$kernel))->find('app:recette:reset'));
        $tester->execute($input);
        $this->em()->clear();

        return $tester;
    }

    /** @return array<string, list<string>> ids of every row, per root entity class */
    private function snapshot(): array
    {
        $snapshot = [];
        foreach ($this->em()->getMetadataFactory()->getAllMetadata() as $meta) {
            if ($meta->isMappedSuperclass || $meta->rootEntityName !== $meta->getName() || User::class === $meta->getName()) {
                continue;
            }
            $ids = array_map(
                static fn (object $entity) => (string) $entity->getId(),
                $this->em()->getRepository($meta->getName())->findAll(),
            );
            sort($ids);
            $snapshot[$meta->getName()] = $ids;
        }
        ksort($snapshot);

        return $snapshot;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
