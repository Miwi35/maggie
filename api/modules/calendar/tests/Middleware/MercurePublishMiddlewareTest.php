<?php

namespace Maggie\Calendar\Tests\Middleware;

use Lcobucci\JWT\Signer\InvalidKeyProvided;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\ChangesetStore;
use Maggie\Core\Mercure\Middleware\MercurePublishMiddleware;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Message\CreateStoreCommand;
use Maggie\Grocery\Message\DeleteStoreCommand;
use Maggie\Grocery\Message\UpdateStoreCommand;
use PHPUnit\Framework\TestCase;
use Psr\Log\AbstractLogger;
use Psr\Log\LogLevel;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;
use Symfony\Component\Uid\Ulid;

class MercurePublishMiddlewareTest extends TestCase
{
    private HubInterface $hub;
    private Security $security;
    private ChangesetStore $changesetStore;
    private User $user;
    /** @var Update[] */
    private array $publishedUpdates = [];

    protected function setUp(): void
    {
        $this->publishedUpdates = [];
        $this->changesetStore = new ChangesetStore();
        $this->hub = $this->createMock(HubInterface::class);
        $this->hub->method('publish')->willReturnCallback(function (Update $update) {
            $this->publishedUpdates[] = $update;

            return 'urn:uuid:'.new Ulid();
        });

        $this->user = new User();
        $this->user->setEmail('test@example.com');
        $this->user->setGoogleId('google-test-id');
        $this->user->setName('Test User');

        $this->security = $this->createMock(Security::class);
        $this->security->method('getUser')->willReturn($this->user);
    }

    private function createPassthroughStack(?object $result = null): StackInterface
    {
        $next = $this->createMock(MiddlewareInterface::class);
        $next->method('handle')->willReturnCallback(
            function (Envelope $envelope) use ($result) {
                if (null !== $result) {
                    return $envelope->with(new HandledStamp($result, 'handler'));
                }

                return $envelope->with(new HandledStamp(null, 'handler'));
            }
        );

        $stack = $this->createMock(StackInterface::class);
        $stack->method('next')->willReturn($next);

        return $stack;
    }

    private function createMiddleware(): MercurePublishMiddleware
    {
        return new MercurePublishMiddleware($this->hub, $this->security, $this->changesetStore);
    }

    /** Indexing runs in the request, with a user: it must not publish a delete on a topic of its own. */
    public function testIndexingCommandsPublishNothing(): void
    {
        $middleware = $this->createMiddleware();

        $middleware->handle($this->received(new DeleteDocumentCommand(indexName: 'events', documentId: (string) new Ulid())), $this->createPassthroughStack());
        $middleware->handle($this->received(new IndexDocumentCommand(entityClass: Event::class, entityId: (string) new Ulid())), $this->createPassthroughStack());

        self::assertSame([], $this->publishedUpdates);
    }

    /** Wrap a command in an envelope with ReceivedStamp (simulates sync transport re-dispatch). */
    private function received(object $command): Envelope
    {
        return new Envelope($command, [new ReceivedStamp('sync')]);
    }

    public function testCreateEventPublishesToUserScopedTopic(): void
    {
        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        $envelope = $this->received(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/events/', $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Meeting', $data['summary']);
        self::assertStringContainsString('/api/events/', $data['@id']);
        self::assertStringNotContainsString('/users/', $data['@id']);
    }

    public function testUpdateEventPublishesToUserScopedTopic(): void
    {
        $event = new Event();
        $event->setSummary('Updated Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        $envelope = $this->received(new UpdateEventCommand(eventId: (string) $event->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/events/', $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Updated Meeting', $data['summary']);
    }

    public function testDeleteEventPublishesToUserScopedTopic(): void
    {
        $eventId = (string) new Ulid();

        $envelope = $this->received(new DeleteEventCommand(eventId: $eventId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/events/'.$eventId, $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/events/'.$eventId, $data['@id']);
        self::assertTrue($data['deleted']);
    }

    public function testCreateAgendaPublishesToUserScopedTopic(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Work');
        $agenda->setColor('#ff0000');
        $agenda->setUser($this->user);

        $envelope = $this->received(new CreateAgendaCommand(userId: (string) $this->user->getId(), name: 'Work', color: '#ff0000'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($agenda));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/agendas/', $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Work', $data['name']);
        self::assertSame('#ff0000', $data['color']);
    }

    public function testUpdateAgendaPublishesToUserScopedTopic(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Personal');
        $agenda->setUser($this->user);

        $envelope = $this->received(new UpdateAgendaCommand(agendaId: (string) $agenda->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($agenda));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/agendas/', $topic);
    }

    public function testDeleteAgendaPublishesToUserScopedTopic(): void
    {
        $agendaId = (string) new Ulid();

        $envelope = $this->received(new DeleteAgendaCommand(agendaId: $agendaId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/agendas/'.$agendaId, $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertTrue($data['deleted']);
    }

    public function testCreateTaskPublishesToUserScopedTopic(): void
    {
        $task = new Task();
        $task->setUser($this->user);
        $task->setTitle('Buy groceries');
        $task->setPriority(TaskPriority::High);
        $task->setCriticality(TaskCriticality::Medium);
        $task->setDueDate(new \DateTimeImmutable('2026-03-25T18:00:00+01:00'));

        $envelope = $this->received(new CreateTaskCommand(userId: (string) $this->user->getId(), title: 'Buy groceries'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($task));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/tasks/', $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Buy groceries', $data['title']);
        self::assertFalse($data['isDone']);
    }

    public function testUpdateTaskPublishesToUserScopedTopic(): void
    {
        $task = new Task();
        $task->setUser($this->user);
        $task->setTitle('Updated task');
        $task->setPriority(TaskPriority::Low);
        $task->setCriticality(TaskCriticality::Critical);

        $envelope = $this->received(new UpdateTaskCommand(taskId: (string) $task->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($task));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/tasks/', $topic);
    }

    public function testDeleteTaskPublishesToUserScopedTopic(): void
    {
        $taskId = (string) new Ulid();

        $envelope = $this->received(new DeleteTaskCommand(taskId: $taskId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/tasks/'.$taskId, $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertTrue($data['deleted']);
    }

    // --- Cookbook entities ---

    public function testCreateRecipePublishesToUserScopedTopic(): void
    {
        $recipe = new Recipe();
        $recipe->setName('Pâtes carbonara');
        $recipe->setServings(4);
        $recipe->setUser($this->user);

        $envelope = $this->received(new CreateRecipeCommand(userId: (string) $this->user->getId(), name: 'Pâtes carbonara'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($recipe));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/recipes/', $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Pâtes carbonara', $data['name']);
    }

    public function testUpdateRecipePublishesToUserScopedTopic(): void
    {
        $recipe = new Recipe();
        $recipe->setName('Updated recipe');
        $recipe->setUser($this->user);

        $envelope = $this->received(new UpdateRecipeCommand(recipeId: (string) $recipe->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($recipe));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/recipes/', $topic);
    }

    public function testDeleteRecipePublishesToUserScopedTopic(): void
    {
        $recipeId = (string) new Ulid();

        $envelope = $this->received(new DeleteRecipeCommand(recipeId: $recipeId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/recipes/'.$recipeId, $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertTrue($data['deleted']);
    }

    public function testCreateStorePublishesToUserScopedTopic(): void
    {
        $store = new Store();
        $store->setName('Boulangerie');
        $store->setVisitOrder(1);
        $store->setUser($this->user);

        $envelope = $this->received(new CreateStoreCommand(userId: (string) $this->user->getId(), name: 'Boulangerie'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($store));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/stores/', $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Boulangerie', $data['name']);
    }

    public function testUpdateStorePublishesToUserScopedTopic(): void
    {
        $store = new Store();
        $store->setName('Updated store');
        $store->setUser($this->user);

        $envelope = $this->received(new UpdateStoreCommand(storeId: (string) $store->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($store));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/stores/', $topic);
    }

    public function testDeleteStorePublishesToUserScopedTopic(): void
    {
        $storeId = (string) new Ulid();

        $envelope = $this->received(new DeleteStoreCommand(storeId: $storeId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/stores/'.$storeId, $topic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertTrue($data['deleted']);
    }

    public function testCreateIngredientPublishesToBothIngredientsAndProductsTopics(): void
    {
        $ingredient = new Ingredient();
        $ingredient->setName('Carotte');
        $ingredient->setCategory(ProductCategory::Produce);
        $ingredient->setUser($this->user);

        $envelope = $this->received(new CreateIngredientCommand(userId: (string) $this->user->getId(), name: 'Carotte', category: 'produce'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($ingredient));

        // Ingredient extends Product → publishes to both topics
        self::assertCount(2, $this->publishedUpdates);

        $ingredientTopic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/ingredients/', $ingredientTopic);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Carotte', $data['name']);

        $productTopic = $this->publishedUpdates[1]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/products/', $productTopic);
    }

    public function testAddGroceryItemPublishesGroceryListTopic(): void
    {
        $list = new GroceryList();
        $list->setUser($this->user);

        $envelope = $this->received(new AddGroceryItemCommand(userId: (string) $this->user->getId(), label: 'Bananes'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($list));

        self::assertCount(1, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/grocery_lists/', $topic);
    }

    public function testCreateMealPublishesToBothMealsAndEventsTopics(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Repas');
        $agenda->setUser($this->user);

        $meal = new Meal();
        $meal->setSlot(MealSlot::Lunch);
        $meal->setSummary('Déjeuner');
        $meal->setAgenda($agenda);
        $meal->setDate(new \DateTimeImmutable('2026-03-20'));

        $envelope = $this->received(new CreateMealCommand(date: '2026-03-20', slot: 'lunch'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($meal));

        self::assertCount(2, $this->publishedUpdates);

        // First update: /api/meals/ topic
        $mealTopic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/meals/', $mealTopic);
        $mealData = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Déjeuner', $mealData['summary']);
        self::assertSame('lunch', $mealData['slot']);

        // Second update: /api/events/ topic (parent class)
        $eventTopic = $this->publishedUpdates[1]->getTopics()[0];
        self::assertStringStartsWith('/users/'.$this->user->getId().'/api/events/', $eventTopic);
        $eventData = json_decode($this->publishedUpdates[1]->getData(), true);
        self::assertSame('Déjeuner', $eventData['summary']);
    }

    public function testDeleteMealPublishesToBothMealsAndEventsTopics(): void
    {
        $mealId = (string) new Ulid();

        $envelope = $this->received(new DeleteMealCommand(mealId: $mealId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(2, $this->publishedUpdates);

        // First: /api/meals/ delete
        $mealTopic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/meals/'.$mealId, $mealTopic);
        $mealData = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertTrue($mealData['deleted']);

        // Second: /api/events/ delete (parent class)
        $eventTopic = $this->publishedUpdates[1]->getTopics()[0];
        self::assertSame('/users/'.$this->user->getId().'/api/events/'.$mealId, $eventTopic);
        $eventData = json_decode($this->publishedUpdates[1]->getData(), true);
        self::assertTrue($eventData['deleted']);
    }

    public function testMealResolvesUserIdFromAgendaRelation(): void
    {
        // No Security user — middleware should resolve userId from Agenda→User (OwnedThroughInterface)
        $this->security = $this->createMock(Security::class);
        $this->security->method('getUser')->willReturn(null);

        $agenda = new Agenda();
        $agenda->setName('Repas');
        $agenda->setUser($this->user);

        $meal = new Meal();
        $meal->setSlot(MealSlot::Dinner);
        $meal->setSummary('Dîner');
        $meal->setAgenda($agenda);
        $meal->setDate(new \DateTimeImmutable('2026-03-20'));

        $envelope = $this->received(new CreateMealCommand(date: '2026-03-20', slot: 'dinner'));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($meal));

        self::assertCount(2, $this->publishedUpdates);
        $topic = $this->publishedUpdates[0]->getTopics()[0];
        self::assertStringContainsString('/users/'.$this->user->getId(), $topic);
    }

    public function testUnrelatedMessageDoesNotPublish(): void
    {
        $envelope = new Envelope(new \stdClass());
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(0, $this->publishedUpdates);
    }

    public function testNoUserDoesNotPublish(): void
    {
        $this->security = $this->createMock(Security::class);
        $this->security->method('getUser')->willReturn(null);

        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        $envelope = $this->received(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(0, $this->publishedUpdates);
    }

    public function testWithoutReceivedStampDoesNotPublish(): void
    {
        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        // No ReceivedStamp → first pass through middleware before transport, should skip publishing
        $envelope = new Envelope(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(0, $this->publishedUpdates);
    }

    // --- Differential updates ---

    public function testUpdateEventPublishesOnlyChangedFields(): void
    {
        $event = new Event();
        $event->setSummary('Updated summary');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        // Pre-populate changeset store with only 'summary' changed
        $this->changesetStore->capture($event, ['summary']);

        $envelope = $this->received(new UpdateEventCommand(eventId: (string) $event->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertArrayHasKey('@id', $data);
        self::assertSame('Updated summary', $data['summary']);
        // Should NOT contain other fields
        self::assertArrayNotHasKey('startAt', $data);
        self::assertArrayNotHasKey('endAt', $data);
    }

    public function testUpdateWithNoChangesetPublishesFullPayload(): void
    {
        $event = new Event();
        $event->setSummary('Full payload');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        // Empty changeset store → get() returns null → full payload
        $envelope = $this->received(new UpdateEventCommand(eventId: (string) $event->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertArrayHasKey('@id', $data);
        self::assertSame('Full payload', $data['summary']);
        self::assertArrayHasKey('startAt', $data);
        self::assertArrayHasKey('endAt', $data);
    }

    public function testUpdateTaskWithCompletedAtMapsToIsDone(): void
    {
        $task = new Task();
        $task->setUser($this->user);
        $task->setTitle('Complete me');
        $task->setPriority(TaskPriority::Medium);
        $task->setCriticality(TaskCriticality::Low);
        $task->setCompletedAt(new \DateTimeImmutable());

        // Doctrine tracks 'completedAt', but payload key is 'isDone'
        $this->changesetStore->capture($task, ['completedAt']);

        $envelope = $this->received(new UpdateTaskCommand(taskId: (string) $task->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($task));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertArrayHasKey('isDone', $data);
        self::assertTrue($data['isDone']);
        self::assertArrayNotHasKey('title', $data);
        self::assertArrayNotHasKey('priority', $data);
    }

    // --- Privacy ---

    /**
     * A public update is delivered to any subscriber whose requested topic
     * matches, whatever their token's mercure.subscribe claim says — so a
     * user could listen to another user's /users/{id}/… topics (MAG-139).
     */
    public function testEveryPublishedUpdateIsPrivate(): void
    {
        $event = new Event();
        $event->setSummary('Secret');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        $this->createMiddleware()->handle(
            $this->received(new CreateEventCommand('Secret', $event->getStartAt(), $event->getEndAt())),
            $this->createPassthroughStack($event),
        );
        $this->createMiddleware()->handle(
            $this->received(new UpdateEventCommand(eventId: (string) $event->getId())),
            $this->createPassthroughStack($event),
        );
        $this->createMiddleware()->handle(
            $this->received(new DeleteEventCommand(eventId: (string) $event->getId())),
            $this->createPassthroughStack(),
        );

        self::assertCount(3, $this->publishedUpdates);
        foreach ($this->publishedUpdates as $update) {
            self::assertTrue($update->isPrivate(), 'Update on '.implode(', ', $update->getTopics()).' is public.');
        }
    }

    // --- Failure reporting ---

    /**
     * The write has already happened when publishing fails, and a worker
     * would retry the whole command if we threw — so nothing is rethrown, but
     * a bad secret must not read like a hub that is merely down (MAG-141).
     */
    public function testAnUnsignableSecretIsLoggedAsCriticalAndNamesTheConfiguration(): void
    {
        $this->hub = $this->createStub(HubInterface::class);
        $this->hub->method('publish')->willThrowException(InvalidKeyProvided::tooShort(256, 144));
        $logger = $this->recordingLogger();

        $event = $this->anEvent();
        $envelope = (new MercurePublishMiddleware($this->hub, $this->security, $this->changesetStore, $logger))->handle(
            $this->received(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt())),
            $this->createPassthroughStack($event),
        );

        self::assertNotNull($envelope->last(HandledStamp::class), 'The write must still succeed.');
        self::assertCount(1, $logger->records);
        self::assertSame(LogLevel::CRITICAL, $logger->records[0]['level']);
        self::assertStringContainsString('MERCURE_JWT_SECRET', $logger->records[0]['message']);
    }

    public function testAnUnavailableHubStaysAnOrdinaryLoggedError(): void
    {
        $this->hub = $this->createStub(HubInterface::class);
        $this->hub->method('publish')->willThrowException(new \RuntimeException('Connection refused'));
        $logger = $this->recordingLogger();

        $event = $this->anEvent();
        (new MercurePublishMiddleware($this->hub, $this->security, $this->changesetStore, $logger))->handle(
            $this->received(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt())),
            $this->createPassthroughStack($event),
        );

        self::assertCount(1, $logger->records);
        self::assertSame(LogLevel::ERROR, $logger->records[0]['level']);
    }

    private function anEvent(): Event
    {
        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        return $event;
    }

    private function recordingLogger(): AbstractLogger
    {
        return new class extends AbstractLogger {
            /** @var list<array{level: mixed, message: string}> */
            public array $records = [];

            public function log($level, string|\Stringable $message, array $context = []): void
            {
                $this->records[] = ['level' => $level, 'message' => (string) $message];
            }
        };
    }
}
