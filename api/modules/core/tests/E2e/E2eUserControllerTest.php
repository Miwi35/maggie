<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Doctrine\Common\DataFixtures\Purger\ORMPurger;
use Doctrine\ORM\EntityManagerInterface;
use Fidry\AliceDataFixtures\LoaderInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Core\E2e\Controller\E2eAccessGuard;
use Maggie\Core\E2e\Controller\E2eUserController;
use Maggie\Core\E2e\Fixture\E2eDateProvider;
use Maggie\Core\E2e\Fixture\E2eFixtureCatalog;
use Maggie\Core\E2e\Fixture\FixtureEffects;
use Maggie\Core\E2e\User\E2eUserFactory;
use Maggie\Core\E2e\User\LoadedObjectsIndexer;
use Maggie\Core\Entity\RefreshToken;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Finance\Doctrine\TransactionListener;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Project;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * The door that gives a journey an account of its own (MAG-577).
 *
 * Like the login, the controller is absent from the `test` container, so it is
 * built by hand from real collaborators; the search index is a recorder, since
 * the default test suite has no cluster. Everything asserted is observable
 * state: status codes, rows, and what was handed to the index.
 */
final class E2eUserControllerTest extends KernelTestCase
{
    private const TOKEN = 'the-e2e-login-token';

    /** @var list<object> what the factory asked the search index to hold */
    private array $indexed = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->purge();
        $this->indexed = [];
    }

    protected function tearDown(): void
    {
        $this->purge();

        parent::tearDown();
    }

    public function testMissingTokenReturns401(): void
    {
        $response = $this->controller()($this->request(['profile' => 'empty'], token: null));

        self::assertSame(Response::HTTP_UNAUTHORIZED, $response->getStatusCode());
        self::assertSame(0, $this->rows(User::class), 'A rejected request must not create the account.');
    }

    public function testWrongTokenReturns401(): void
    {
        $response = $this->controller()($this->request(['profile' => 'empty'], token: 'not-the-token'));

        self::assertSame(Response::HTTP_UNAUTHORIZED, $response->getStatusCode());
        self::assertSame(0, $this->rows(User::class));
    }

    public function testOutsideE2eItIsNotFound(): void
    {
        $this->expectException(NotFoundHttpException::class);

        $this->controller(environment: 'prod')($this->request(['profile' => 'empty']));
    }

    public function testUnknownProfileReturns400AndCreatesNothing(): void
    {
        $response = $this->controller()($this->request(['profile' => 'nope']));

        self::assertSame(Response::HTTP_BAD_REQUEST, $response->getStatusCode());
        self::assertSame(['empty', 'standard', 'stock'], $this->decode($response)['profiles']);
        self::assertSame(0, $this->rows(User::class));
    }

    public function testMalformedBodyReturns400(): void
    {
        $response = $this->controller()($this->request(rawBody: 'not json'));

        self::assertSame(Response::HTTP_BAD_REQUEST, $response->getStatusCode());
        self::assertSame(0, $this->rows(User::class));
    }

    public function testEmptyProfileIsAnAccountAndItsPreferencesOnly(): void
    {
        $response = $this->controller()($this->request(['profile' => 'empty']));

        self::assertSame(Response::HTTP_OK, $response->getStatusCode());
        $payload = $this->decode($response);

        self::assertMatchesRegularExpression('/^journey-[0-9a-z]{26}@e2e\.test$/', $payload['user']['email']);
        self::assertNull($payload['neighbour']);
        self::assertSame(1, $this->rows(User::class));
        self::assertSame(1, $this->rows(UserPreference::class));
        self::assertSame(0, $this->rows(Recipe::class));
        self::assertSame(0, $this->rows(Account::class));
        self::assertSame(0, $this->rows(Agenda::class));
    }

    public function testTheAnswerIsTheLoginsAndTheAccountCanBeSignedIn(): void
    {
        $payload = $this->decode($this->controller()($this->request(['profile' => 'empty'])));

        self::assertNotEmpty($payload['token']);
        self::assertNotEmpty($payload['refreshToken']);
        self::assertNotEmpty($payload['mercureToken']);

        $user = $this->entityManager()->getRepository(User::class)->findOneBy(['email' => $payload['user']['email']]);
        self::assertNotNull($user);
        self::assertSame((string) $user->getId(), $payload['user']['id']);
        self::assertNotNull(
            $this->entityManager()->getRepository(RefreshToken::class)->findOneBy(['refreshToken' => $payload['refreshToken']]),
            'The session must be usable for as long as the journey runs.',
        );
    }

    public function testStandardProfileGivesTheSeedsDataToTheNewUser(): void
    {
        $payload = $this->decode($this->controller()($this->request(['profile' => 'standard'])));

        $user = $this->entityManager()->getRepository(User::class)->findOneBy(['email' => $payload['user']['email']]);
        self::assertNotNull($user);

        $recipes = $this->entityManager()->getRepository(Recipe::class)->findBy(['user' => $user]);
        self::assertEqualsCanonicalizing(['Gratin de courgettes', 'Pâtes à la tomate'], array_map(static fn (Recipe $r): string => $r->getName(), $recipes));
        self::assertGreaterThan(0, $this->entityManager()->getRepository(Account::class)->count(['user' => $user]));
        self::assertGreaterThan(0, $this->entityManager()->getRepository(Agenda::class)->count(['user' => $user]));

        // The manifest speaks the seed's references, with this user's ids.
        $references = $payload['manifest']['references'];
        self::assertSame((string) $user->getId(), $references['e2e_user']['id']);
        self::assertContains($references['e2e_recipe_gratin']['id'], array_map(static fn (Recipe $r): string => (string) $r->getId(), $recipes));
        self::assertSame($payload['neighbour']['id'], $references['e2e_other_user']['id']);
    }

    public function testStockProfileGivesTheStockAccountItsOwnProject(): void
    {
        $payload = $this->decode($this->controller()($this->request(['profile' => 'stock'])));

        self::assertNull($payload['neighbour']);
        self::assertSame($payload['user']['id'], $payload['manifest']['references']['e2e_stock_user']['id']);

        $user = $this->entityManager()->getRepository(User::class)->find($payload['user']['id']);
        self::assertSame(1, $this->entityManager()->getRepository(Project::class)->count(['user' => $user]));
        self::assertSame(0, $this->entityManager()->getRepository(Recipe::class)->count(['user' => $user]), 'The stock account holds no recipe.');
    }

    public function testStandardProfileIsIndexedBeforeTheAnswer(): void
    {
        $payload = $this->decode($this->controller()($this->request(['profile' => 'standard'])));

        $indexedClasses = array_unique(array_map(static fn (object $o): string => $o::class, $this->indexed));
        self::assertContains(Recipe::class, $indexedClasses);
        self::assertContains(Meal::class, $indexedClasses);

        $ids = array_map(static fn (object $o): string => (string) $o->getId(), $this->indexed);
        self::assertContains($payload['manifest']['references']['e2e_recipe_gratin']['id'], $ids);
    }

    public function testTheSliceCarriesNothingOfTheOtherSeededAccounts(): void
    {
        $this->controller()($this->request(['profile' => 'standard']));

        $emails = array_map(static fn (User $u): string => $u->getEmail(), $this->entityManager()->getRepository(User::class)->findAll());
        self::assertCount(2, $emails, 'The journey user and its neighbour, and no seeded account.');
        foreach ($emails as $email) {
            self::assertStringEndsWith('@e2e.test', $email);
        }
    }

    public function testTwoUsersDoNotSeeEachOthersRows(): void
    {
        $first = $this->decode($this->controller()($this->request(['profile' => 'standard'])));
        $second = $this->decode($this->controller()($this->request(['profile' => 'standard'])));

        self::assertNotSame($first['user']['id'], $second['user']['id']);
        self::assertNotSame($first['user']['email'], $second['user']['email']);

        $repository = $this->entityManager()->getRepository(Recipe::class);
        $firstOwner = $this->entityManager()->getRepository(User::class)->find($first['user']['id']);
        $secondOwner = $this->entityManager()->getRepository(User::class)->find($second['user']['id']);

        self::assertCount(2, $repository->findBy(['user' => $firstOwner]));
        self::assertCount(2, $repository->findBy(['user' => $secondOwner]));
        self::assertSame(
            $this->rows(Recipe::class),
            $repository->count(['user' => $firstOwner]) + $repository->count(['user' => $secondOwner])
                + $repository->count(['user' => $this->entityManager()->getRepository(User::class)->find($first['neighbour']['id'])])
                + $repository->count(['user' => $this->entityManager()->getRepository(User::class)->find($second['neighbour']['id'])]),
            'Every recipe belongs to one of the two journey users or their neighbours.',
        );
        self::assertNotSame($first['manifest']['references']['e2e_recipe_gratin']['id'], $second['manifest']['references']['e2e_recipe_gratin']['id']);
    }

    private function controller(string $environment = 'e2e', string $configuredToken = self::TOKEN): E2eUserController
    {
        $container = self::getContainer();
        $loader = $container->get('fidry_alice_data_fixtures.loader.doctrine');
        self::assertInstanceOf(LoaderInterface::class, $loader);
        $projectDir = $container->getParameter('kernel.project_dir');

        $indexer = new class($this->indexed) implements LoadedObjectsIndexer {
            /** @param list<object> $indexed */
            public function __construct(private array &$indexed)
            {
            }

            public function index(array $objects): void
            {
                array_push($this->indexed, ...array_values($objects));
            }
        };

        return new E2eUserController(
            new E2eAccessGuard($environment, $configuredToken),
            new E2eUserFactory(
                $this->entityManager(),
                $container->get(E2eDateProvider::class),
                $loader,
                new FixtureEffects([$container->get(TransactionListener::class)]),
                new E2eFixtureCatalog($projectDir),
                $indexer,
                $projectDir,
            ),
            E2eLoginControllerTest::sessionFactory(),
        );
    }

    /** @param array<string, mixed>|null $body */
    private function request(?array $body = null, ?string $token = self::TOKEN, ?string $rawBody = null): Request
    {
        return Request::create(
            '/api/auth/e2e/users',
            'POST',
            [],
            [],
            [],
            null === $token ? [] : ['HTTP_X_E2E_TOKEN' => $token],
            $rawBody ?? json_encode($body ?? [], JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function decode(Response $response): array
    {
        return json_decode((string) $response->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param class-string $class */
    private function rows(string $class): int
    {
        return $this->entityManager()->getRepository($class)->count([]);
    }

    private function purge(): void
    {
        (new ORMPurger($this->entityManager()))->purge();
        $this->entityManager()->clear();
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
