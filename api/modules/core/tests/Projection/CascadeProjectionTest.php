<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Projection;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Identifier\CanonicalId;
use Maggie\Core\Mercure\MercureTopic;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Message\DeleteAccountCommand;
use Maggie\Finance\Message\DeleteCategoryCommand;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\DeleteStoreCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * What the database deletes or empties on its own (`ON DELETE CASCADE` / `SET NULL`)
 * is announced like any other change, without a manual call (MAG-896). Rows are
 * built outside any message and the command is dispatched with nobody logged in.
 */
class CascadeProjectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../../../cookbook/tests/MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        $this->em()->clear();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get(MessageBusInterface::class);
    }

    private function user(): User
    {
        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function topic(string $class, object $row): string
    {
        return MercureTopic::item(MercureTopic::collection($class), CanonicalId::of((string) $row->getId()));
    }

    /** @return list<string> */
    private function deletedDocumentIds(): array
    {
        $ids = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $ids[] = $message->documentId;
            }
        }

        return $ids;
    }

    private function account(): Account
    {
        $account = (new Account())->setName('Courant')->setType(AccountType::Checking)->setUser($this->user());
        $this->em()->persist($account);

        return $account;
    }

    private function category(string $name, ?Category $parent = null): Category
    {
        $category = (new Category())->setName($name)->setUser($this->user())->setParent($parent);
        $this->em()->persist($category);

        return $category;
    }

    private function transaction(Account $account, ?Category $category = null): Transaction
    {
        $transaction = (new Transaction())->setUser($this->user())->setAccount($account)->setLabel('Achat')->setAmountCents(-1200)->setCategory($category);
        $this->em()->persist($transaction);

        return $transaction;
    }

    /** Saved, then every announcement of the set-up forgotten: the test watches the command only. */
    private function settle(): void
    {
        $this->em()->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function planMeal(string $recipeRef, string $when): Meal
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }

    public function testDeletingAnAccountAnnouncesEachOfItsTransactionsDeletedOnce(): void
    {
        $account = $this->account();
        $first = $this->transaction($account);
        $second = $this->transaction($account);
        $this->settle();

        $this->bus()->dispatch(new DeleteAccountCommand(userId: (string) $this->user()->getId(), accountId: (string) $account->getId()));

        $this->assertMercureDeletePublished($this->topic(Account::class, $account));
        $this->assertMercureDeletePublished($this->topic(Transaction::class, $first));
        $this->assertMercureDeletePublished($this->topic(Transaction::class, $second));
        self::assertContains(CanonicalId::of((string) $first->getId()), $this->deletedDocumentIds());
        self::assertContains(CanonicalId::of((string) $second->getId()), $this->deletedDocumentIds());
    }

    public function testDeletingACategoryAnnouncesItsSubCategoriesOnEveryLevel(): void
    {
        $root = $this->category('Maison');
        $child = $this->category('Travaux', $root);
        $grandChild = $this->category('Peinture', $child);
        $this->settle();

        $this->bus()->dispatch(new DeleteCategoryCommand(userId: (string) $this->user()->getId(), categoryId: (string) $root->getId()));

        $this->assertMercureDeletePublished($this->topic(Category::class, $root));
        $this->assertMercureDeletePublished($this->topic(Category::class, $child));
        $this->assertMercureDeletePublished($this->topic(Category::class, $grandChild));
    }

    public function testDeletingARecurringEventAnnouncesItsExceptionInstances(): void
    {
        $agenda = $this->em()->find(Agenda::class, $this->getFixture('repas_agenda')->getId());
        $start = new \DateTimeImmutable('+3 days 10:00');
        $series = (new Event())->setSummary('Sport')->setAgenda($agenda)->setStartAt($start)->setEndAt($start->modify('+1 hour'))->setRrule('FREQ=WEEKLY');
        $exception = (new Event())->setSummary('Sport (déplacé)')->setAgenda($agenda)->setStartAt($start->modify('+1 day'))
            ->setEndAt($start->modify('+1 day +1 hour'))->setRecurringEvent($series)->setOriginalStartAt($start->modify('+7 days'));
        $this->em()->persist($series);
        $this->em()->persist($exception);
        $this->settle();

        $this->bus()->dispatch(new DeleteEventCommand(eventId: (string) $series->getId()));

        $this->assertMercureDeletePublished($this->topic(Event::class, $series));
        $this->assertMercureDeletePublished($this->topic(Event::class, $exception));
        self::assertContains(CanonicalId::of((string) $exception->getId()), $this->deletedDocumentIds());
    }

    public function testDeletingARecipePublishesTheTimerThatNamedItWithoutIt(): void
    {
        $recipe = $this->em()->find(Recipe::class, $this->getFixture('pasta')->getId());
        $timer = new CookingTimer($this->user(), 'Eau', $recipe, 1, 60, new \DateTimeImmutable());
        $this->em()->persist($timer);
        $this->settle();

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: (string) $recipe->getId()));

        $this->assertMercurePublishedOnce($this->topic(CookingTimer::class, $timer));
        $payload = $this->mercurePayloadsOn($this->topic(CookingTimer::class, $timer))[0];
        self::assertArrayNotHasKey('deleted', $payload);
        self::assertArrayHasKey('recipe', $payload);
        self::assertNull($payload['recipe']);
    }

    public function testDeletingARecipeReindexesThePastMealsThatKeepOtherRecipesWithoutNamingIt(): void
    {
        $meal = $this->planMeal('pasta', '-3 days');
        $mealId = (string) $meal->getId();
        $meal = $this->em()->find(Meal::class, $mealId);
        $meal->addRecipe($this->em()->find(Recipe::class, $this->getFixture('gratin')->getId()));
        $this->settle();
        $pastaId = (string) $this->getFixture('pasta')->getId();

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: $pastaId));

        $this->assertMercurePublishedOnce('/api/meals/'.CanonicalId::of($mealId));
        self::assertSame([$mealId], array_values(array_filter($this->reindexedIdsOf(Meal::class), static fn (string $id) => $id === $mealId)));
        self::assertStringNotContainsString(
            CanonicalId::of($pastaId),
            json_encode($this->mercurePayloadsOn('/api/meals/'.CanonicalId::of($mealId))[0], JSON_THROW_ON_ERROR),
            'The published meal still names the deleted recipe',
        );
    }

    public function testARowTheOrmAlreadyDeletesIsPublishedOnce(): void
    {
        $agenda = $this->em()->find(Agenda::class, $this->getFixture('repas_agenda')->getId());
        $start = new \DateTimeImmutable('+3 days 10:00');
        $series = (new Event())->setSummary('Sport')->setAgenda($agenda)->setStartAt($start)->setEndAt($start->modify('+1 hour'))->setRrule('FREQ=WEEKLY');
        $exception = (new Event())->setSummary('Sport (déplacé)')->setAgenda($agenda)->setStartAt($start->modify('+1 day'))
            ->setEndAt($start->modify('+1 day +1 hour'))->setRecurringEvent($series)->setOriginalStartAt($start->modify('+7 days'));
        $this->em()->persist($series);
        $this->em()->persist($exception);
        $this->settle();

        $this->bus()->dispatch(new DeleteAgendaCommand(agendaId: (string) $agenda->getId()));

        $this->assertMercureDeletePublished($this->topic(Event::class, $series));
        $this->assertMercureDeletePublished($this->topic(Event::class, $exception));
    }

    public function testADeletionWithoutCascadeAddsNothing(): void
    {
        $store = (new Store())->setName('Marché')->setUser($this->user());
        $this->em()->persist($store);
        $this->settle();

        $this->bus()->dispatch(new DeleteStoreCommand(storeId: (string) $store->getId()));

        self::assertCount(1, $this->getMercureHub()->getUpdates());
        $this->assertMercureDeletePublished($this->topic(Store::class, $store));
    }
}
