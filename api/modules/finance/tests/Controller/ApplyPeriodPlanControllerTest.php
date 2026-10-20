<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** Writing the plan of a period in one gesture: POST /api/finance/annual-plan (MAG-462). */
class ApplyPeriodPlanControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/finance/annual-plan', [], [], ['CONTENT_TYPE' => 'application/json'], '{}');

        self::assertResponseStatusCodeSame(401);
    }

    public function testABodyThatIsNotJsonReturns400(): void
    {
        $this->login();

        $this->client->request('POST', '/api/finance/annual-plan', [], [], $this->headers(), '{nope');

        self::assertResponseStatusCodeSame(400);
    }

    public function testABodyThatIsNotAnObjectReturns400(): void
    {
        $this->login();

        $this->client->request('POST', '/api/finance/annual-plan', [], [], $this->headers(), '"2026-Q2"');

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function plansRefusedAsAWhole(): iterable
    {
        yield 'no period' => [['items' => [['kind' => 'one_off', 'name' => 'x', 'amountCents' => 1]]]];
        yield 'a period that is no string' => [['period' => 2026, 'items' => [['kind' => 'one_off', 'name' => 'x', 'amountCents' => 1]]]];
        yield 'a month is not a period' => [['period' => '2026-04', 'items' => [['kind' => 'one_off', 'name' => 'x', 'amountCents' => 1]]]];
        yield 'a fifth quarter' => [['period' => '2026-Q5', 'items' => [['kind' => 'one_off', 'name' => 'x', 'amountCents' => 1]]]];
        yield 'no items' => [['period' => '2026-Q2']];
        yield 'items that are no list' => [['period' => '2026-Q2', 'items' => 'tout']];
        yield 'an empty list of items' => [['period' => '2026-Q2', 'items' => []]];
        yield 'items keyed by name' => [['period' => '2026-Q2', 'items' => ['a' => ['kind' => 'one_off', 'name' => 'x', 'amountCents' => 1]]]];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('plansRefusedAsAWhole')]
    public function testAPlanThatIsNotOneReturns400WithoutAnItemToBlame(array $body): void
    {
        $this->login();

        $this->post($body);

        self::assertResponseStatusCodeSame(400);
        self::assertNotSame('', $this->json()['error']);
        self::assertSame(0, $this->countEnvelopes('Hellfest'));
    }

    public function testTooLongAPlanIsRefused(): void
    {
        $this->login();

        $items = array_map(
            static fn (int $i): array => ['kind' => 'one_off', 'name' => "Projet {$i}", 'amountCents' => 100],
            range(1, 101),
        );
        $this->post(['period' => '2026-Q2', 'items' => $items]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->json()['index']);
        self::assertSame(0, $this->countEnvelopes('Projet 1'));
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function itemsRefused(): iterable
    {
        yield 'not an object' => ['salade'];
        yield 'no kind' => [['categoryId' => '{food}', 'amountCents' => 100]];
        yield 'an unknown kind' => [['kind' => 'budget', 'categoryId' => '{food}', 'amountCents' => 100]];
        yield 'an annual envelope on a quarter' => [['kind' => 'annual', 'categoryId' => '{food}', 'amountCents' => 100]];
        yield 'no category' => [['kind' => 'monthly', 'amountCents' => 100]];
        yield 'a category that is no id' => [['kind' => 'monthly', 'categoryId' => 'food', 'amountCents' => 100]];
        yield 'a category of someone else' => [['kind' => 'monthly', 'categoryId' => '{other_leisure}', 'amountCents' => 100]];
        yield 'an amount that is no integer' => [['kind' => 'monthly', 'categoryId' => '{food}', 'amountCents' => '100']];
        yield 'a negative amount' => [['kind' => 'monthly', 'categoryId' => '{food}', 'amountCents' => -1]];
        yield 'an absurd amount' => [['kind' => 'monthly', 'categoryId' => '{food}', 'amountCents' => 100_000_000_001]];
        yield 'a currency in lower case' => [['kind' => 'monthly', 'categoryId' => '{food}', 'amountCents' => 100, 'currency' => 'eur']];
        yield 'seasons that overlap' => [['kind' => 'monthly', 'categoryId' => '{food}', 'amountCents' => 100, 'seasons' => [
            ['fromMonth' => 4, 'toMonth' => 5, 'amountCents' => 1],
            ['fromMonth' => 5, 'toMonth' => 6, 'amountCents' => 2],
        ]]];
        yield 'a one-off envelope without a name' => [['kind' => 'one_off', 'name' => '  ', 'amountCents' => 100]];
        yield 'a one-off envelope with a name too long' => [['kind' => 'one_off', 'name' => 'xxx'.str_repeat('x', 120), 'amountCents' => 100]];
        yield 'an event without a label' => [['kind' => 'event', 'categoryId' => '{leisure}', 'amountCents' => 100, 'month' => '2026-05']];
        yield 'an event without a month' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 100]];
        yield 'an event with a month that is not one' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 100, 'month' => '2026-13']];
        yield 'an event before the period' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 100, 'month' => '2026-03']];
        yield 'an event after the period' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 100, 'month' => '2026-07']];
        yield 'an event at zero' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 0, 'month' => '2026-05']];
        yield 'an event on an account of someone else' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 100, 'month' => '2026-05', 'accountId' => '{other_checking}']];
        yield 'an event on an account that is no id' => [['kind' => 'event', 'categoryId' => '{leisure}', 'label' => 'Yoga', 'amountCents' => 100, 'month' => '2026-05', 'accountId' => 'courant']];
    }

    /** @param array<string, mixed>|string $item */
    #[DataProvider('itemsRefused')]
    public function testAnItemThatIsRefusedNamesItsLineAndWritesNothing(array|string $item): void
    {
        $this->login();

        // The refused item comes second: the first one is fine and must not be written.
        $this->post(['period' => '2026-Q2', 'items' => [$this->ok(), $this->resolve($item)]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(1, $this->json()['index']);
        self::assertNotSame('', $this->json()['error']);
        $this->assertNothingWritten();
    }

    public function testTheSameLineTwiceIsRefused(): void
    {
        $this->login();
        $monthly = ['kind' => 'monthly', 'categoryId' => (string) $this->getFixture('food')->getId(), 'amountCents' => 100];

        $this->post(['period' => '2026-Q2', 'items' => [$monthly, $monthly]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(1, $this->json()['index']);
        self::assertStringContainsString('twice', $this->json()['error']);
        self::assertSame(0, $this->countMonthly('food', 2026, 5));
    }

    public function testTheSameExpenseTwiceIsRefusedWhateverTheCase(): void
    {
        $this->login();

        $this->post(['period' => '2026-Q2', 'items' => [
            $this->event('Yoga', 30000, '2026-05'),
            $this->event('yoga', 30000, '2026-05'),
        ]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(1, $this->json()['index']);
        self::assertSame(0, $this->countEvents('Yoga'));
    }

    public function testAPlanRefusedOnItsLastLineLeavesNothingBehind(): void
    {
        $this->login();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $envelopes = \count($em->getRepository(Envelope::class)->findAll());
        $transactions = \count($em->getRepository(Transaction::class)->findAll());
        $april = $em->getRepository(Envelope::class)->findOneBy(['month' => 4, 'year' => 2026, 'amountCents' => 45000]);
        $aprilId = (string) $april->getId();

        $this->post(['period' => '2026-Q2', 'items' => [
            ['kind' => 'monthly', 'categoryId' => $this->id('food'), 'amountCents' => 50000],
            ['kind' => 'monthly', 'categoryId' => $this->id('leisure'), 'amountCents' => 25000],
            ['kind' => 'one_off', 'name' => 'Hellfest 2026', 'amountCents' => 60000],
            $this->event('Kiné', 9000, '2026-06'),
            // The seventh of July: outside the quarter. Only this line is wrong.
            $this->event('Yoga', 30000, '2026-07'),
        ]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(4, $this->json()['index']);

        $em->clear();
        self::assertCount($envelopes, $em->getRepository(Envelope::class)->findAll());
        self::assertCount($transactions, $em->getRepository(Transaction::class)->findAll());
        self::assertSame(45000, $em->getRepository(Envelope::class)->find($aprilId)->getAmountCents(), 'An existing envelope is not touched either.');
        $this->assertNothingPublished();
    }

    public function testAPlanWhoseLastLineIsSomeoneElsesCategoryLeavesNothingBehind(): void
    {
        $this->login();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $envelopes = \count($em->getRepository(Envelope::class)->findAll());

        // The first line is fine and would be written first: the owner of the
        // last line's category is only known once the whole plan has been read.
        $this->post(['period' => '2026-Q2', 'items' => [
            ['kind' => 'monthly', 'categoryId' => $this->id('food'), 'amountCents' => 50000],
            ['kind' => 'monthly', 'categoryId' => $this->id('other_leisure'), 'amountCents' => 25000],
        ]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(1, $this->json()['index']);
        $em->clear();
        self::assertCount($envelopes, $em->getRepository(Envelope::class)->findAll());
        $this->assertNothingPublished();
    }

    public function testApplyingAQuarterWritesItsEnvelopesAndItsPlannedExpenses(): void
    {
        $this->login();
        $aprilFood = $this->monthly('food', 2026, 4);
        $aprilId = (string) $aprilFood->getId();

        $this->post(['period' => '2026-Q2', 'items' => [
            [
                'kind' => 'monthly',
                'categoryId' => $this->id('food'),
                'amountCents' => 50000,
                'seasons' => [['fromMonth' => 6, 'toMonth' => 6, 'amountCents' => 70000]],
            ],
            ['kind' => 'monthly', 'categoryId' => $this->id('leisure'), 'amountCents' => 25000, 'currency' => 'USD'],
            ['kind' => 'one_off', 'name' => 'Hellfest 2026', 'amountCents' => 60000],
            $this->event('Kiné', 9000, '2026-06'),
        ]]);

        self::assertResponseIsSuccessful();
        $json = $this->json();
        self::assertTrue($json['success']);
        self::assertSame('2026-Q2', $json['period']['key']);
        self::assertSame([0, 1, 2, 3], array_column($json['applied'], 'index'));
        self::assertSame(['monthly', 'monthly', 'one_off', 'event'], array_column($json['applied'], 'kind'));
        // April food existed: it is updated, May and June are created.
        self::assertSame([2, 3, 1, 1], array_column($json['applied'], 'created'));
        self::assertSame(1, $json['applied'][0]['updated']);
        self::assertCount(3, $json['applied'][0]['ids']);
        self::assertContains($aprilId, $json['applied'][0]['ids']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $april = $this->monthly('food', 2026, 4);
        self::assertSame($aprilId, (string) $april->getId(), 'The existing envelope is updated, not duplicated.');
        self::assertSame(50000, $april->getAmountCents());
        self::assertSame(50000, $this->monthly('food', 2026, 5)->getMonthAmountCents());
        self::assertSame(70000, $this->monthly('food', 2026, 6)->getMonthAmountCents(), 'The season of June applies to June alone.');
        foreach ([4, 5, 6] as $month) {
            $leisure = $this->monthly('leisure', 2026, $month);
            self::assertSame(25000, $leisure->getAmountCents());
            self::assertSame('USD', $leisure->getCurrency());
            self::assertSame(BudgetMode::Monthly, $leisure->getMode());
        }

        $hellfest = $em->getRepository(Envelope::class)->findOneBy(['name' => 'Hellfest 2026']);
        self::assertSame(BudgetMode::OneOff, $hellfest->getMode());
        self::assertSame(60000, $hellfest->getAmountCents());
        self::assertNull($hellfest->getCategory());
        self::assertNull($hellfest->getYear());

        $dentist = $em->getRepository(Transaction::class)->findOneBy(['label' => 'Kiné', 'status' => TransactionStatus::Planned]);
        self::assertNotNull($dentist);
        self::assertSame(-9000, $dentist->getAmountCents());
        self::assertSame('2026-06-15', $dentist->getBookedAt()->format('Y-m-d'));
        self::assertSame($this->id('leisure'), (string) $dentist->getCategory()->getId());
        self::assertSame('Compte courant', $dentist->getAccount()->getName(), 'The first checking account is the default one.');

        $this->assertMercureUpdatePublished('/api/envelopes/');
        $this->assertMercureUpdatePublished('/api/transactions/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
        $this->assertElasticsearchIndexDispatched(Transaction::class);
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $json['applied'][3]['ids'][0]);
    }

    public function testApplyingAYearWritesItsAnnualEnvelopes(): void
    {
        $this->login();

        $this->post(['period' => '2027', 'items' => [
            ['kind' => 'annual', 'categoryId' => $this->id('leisure'), 'amountCents' => 240000],
            $this->event('Voyage', 90000, '2027-08'),
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame('2027', $this->json()['period']['key']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $annual = $em->getRepository(Envelope::class)->findOneBy(['mode' => BudgetMode::Annual, 'year' => 2027]);
        self::assertSame(240000, $annual->getAmountCents());
        self::assertNull($annual->getMonth());
        self::assertSame($this->id('leisure'), (string) $annual->getCategory()->getId());
        self::assertSame('2027-08-15', $em->getRepository(Transaction::class)->findOneBy(['label' => 'Voyage'])->getBookedAt()->format('Y-m-d'));
    }

    public function testReplayingAPlanUpdatesWhatItCreatedAndDuplicatesNothing(): void
    {
        $this->login();
        $plan = ['period' => '2026-Q2', 'items' => [
            ['kind' => 'monthly', 'categoryId' => $this->id('leisure'), 'amountCents' => 25000],
            ['kind' => 'one_off', 'name' => 'Hellfest 2026', 'amountCents' => 60000],
            $this->event('Kiné', 9000, '2026-06'),
        ]];

        $this->post($plan);
        self::assertResponseIsSuccessful();
        $first = $this->json()['applied'];

        $plan['items'][0]['amountCents'] = 30000;
        $plan['items'][1]['name'] = 'HELLFEST 2026';
        $plan['items'][1]['amountCents'] = 65000;
        $plan['items'][2] = $this->event('kiné', 12000, '2026-06');
        $this->resetMercure();
        $this->post($plan);

        self::assertResponseIsSuccessful();
        $second = $this->json()['applied'];
        self::assertSame([0, 0, 0], array_column($second, 'created'));
        self::assertSame([3, 1, 1], array_column($second, 'updated'));
        self::assertSame(array_column($first, 'ids'), array_column($second, 'ids'));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(3, $this->countMonthly('leisure', 2026, 4) + $this->countMonthly('leisure', 2026, 5) + $this->countMonthly('leisure', 2026, 6));
        self::assertSame(30000, $this->monthly('leisure', 2026, 5)->getAmountCents());
        self::assertSame(1, $this->countEnvelopes('Hellfest'));
        self::assertSame(65000, $em->getRepository(Envelope::class)->findOneBy(['name' => 'Hellfest 2026'])->getAmountCents());
        self::assertSame(1, $this->countEvents('Kiné'));
        self::assertSame(-12000, $em->getRepository(Transaction::class)->findOneBy(['label' => 'Kiné'])->getAmountCents());
        $this->assertMercureUpdatePublished('/api/envelopes/');
    }

    public function testAnExpenseAlreadyPlannedIsUpdatedInPlaceWhateverTheCaseOfItsLabel(): void
    {
        $this->login();
        $trip = $this->transaction('Voyage en Norvège');
        $tripId = (string) $trip->getId();

        $this->post(['period' => '2026-Q2', 'items' => [$this->event('voyage en norvège', 110000, '2026-05')]]);

        self::assertResponseIsSuccessful();
        self::assertSame(0, $this->json()['applied'][0]['created']);
        self::assertSame(1, $this->json()['applied'][0]['updated']);
        self::assertSame([$tripId], $this->json()['applied'][0]['ids']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(-110000, $em->getRepository(Transaction::class)->find($tripId)->getAmountCents());
        self::assertSame(1, $this->countEvents('Voyage en Norvège'));
    }

    public function testAnExpenseTheOwnerAlreadyCommittedIsLeftAlone(): void
    {
        $this->login();
        $gift = $this->transaction('Cadeau');

        $this->post(['period' => '2026-Q1', 'items' => [$this->event('Cadeau', 99000, '2026-02')]]);

        self::assertResponseIsSuccessful();
        self::assertSame(0, $this->json()['applied'][0]['created']);
        self::assertSame(0, $this->json()['applied'][0]['updated']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $kept = $em->getRepository(Transaction::class)->find((string) $gift->getId());
        self::assertSame(-8000, $kept->getAmountCents());
        self::assertSame(TransactionStatus::Committed, $kept->getStatus());
        self::assertSame(1, $this->countEvents('Cadeau'));
    }

    public function testAnExpenseCanBePlannedOnAnotherAccountOfTheOwner(): void
    {
        $this->login();

        $this->post(['period' => '2026-Q2', 'items' => [
            $this->event('Yoga', 30000, '2026-05') + ['accountId' => $this->id('checking')],
        ]]);

        self::assertResponseIsSuccessful();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame($this->id('checking'), (string) $em->getRepository(Transaction::class)->findOneBy(['label' => 'Yoga'])->getAccount()->getId());
    }

    public function testThePlanOfSomeoneElseIsNeverTouched(): void
    {
        $this->login();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $strangers = $em->getRepository(Transaction::class)->findOneBy(['label' => 'Festival du voisin']);

        $this->post(['period' => '2026-Q1', 'items' => [$this->event('Festival du voisin', 1000, '2026-02')]]);

        self::assertResponseIsSuccessful();
        self::assertSame(1, $this->json()['applied'][0]['created']);
        $em->clear();
        self::assertSame(-99000, $em->getRepository(Transaction::class)->find((string) $strangers->getId())->getAmountCents());
    }

    /** @param array<string, mixed> $body */
    private function post(array $body): void
    {
        $this->client->request('POST', '/api/finance/annual-plan', [], [], $this->headers(), json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, string> */
    private function headers(): array
    {
        return ['CONTENT_TYPE' => 'application/json'] + $this->authHeaders();
    }

    private function login(): void
    {
        $this->loadFixtures('period_plan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    /** A line that is fine, to put before the one that is not. */
    /** @return array<string, mixed> */
    private function ok(): array
    {
        return ['kind' => 'monthly', 'categoryId' => $this->id('leisure'), 'amountCents' => 99999];
    }

    /** @return array<string, mixed> */
    private function event(string $label, int $amountCents, string $month): array
    {
        return ['kind' => 'event', 'categoryId' => $this->id('leisure'), 'label' => $label, 'amountCents' => $amountCents, 'month' => $month];
    }

    /**
     * @param array<string, mixed>|string $item
     *
     * @return array<string, mixed>|string
     */
    private function resolve(array|string $item): array|string
    {
        if (\is_string($item)) {
            return $item;
        }

        return array_map(function (mixed $value): mixed {
            if (\is_string($value) && 1 === preg_match('/^\{(\w+)\}$/', $value, $matches)) {
                return $this->id($matches[1]);
            }

            return $value;
        }, $item);
    }

    private function monthly(string $category, int $year, int $month): Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return $em->getRepository(Envelope::class)->findOneBy([
            'category' => $this->getFixture($category)->getId(),
            'mode' => BudgetMode::Monthly,
            'year' => $year,
            'month' => $month,
        ]) ?? throw new \LogicException("No monthly envelope for {$category} in {$year}-{$month}.");
    }

    private function countMonthly(string $category, int $year, int $month): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return \count($em->getRepository(Envelope::class)->findBy([
            'category' => $this->getFixture($category)->getId(),
            'mode' => BudgetMode::Monthly,
            'year' => $year,
            'month' => $month,
        ]));
    }

    private function countEnvelopes(string $namePrefix): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return \count(array_filter(
            $em->getRepository(Envelope::class)->findBy(['mode' => BudgetMode::OneOff]),
            static fn (Envelope $envelope): bool => str_starts_with((string) $envelope->getName(), $namePrefix) || str_starts_with(mb_strtolower((string) $envelope->getName()), mb_strtolower($namePrefix)),
        ));
    }

    private function countEvents(string $label): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return \count(array_filter(
            $em->getRepository(Transaction::class)->findAll(),
            static fn (Transaction $transaction): bool => 0 === strcasecmp($transaction->getLabel(), $label),
        ));
    }

    private function transaction(string $label): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return $em->getRepository(Transaction::class)->findOneBy(['label' => $label])
            ?? throw new \LogicException("No transaction {$label}.");
    }

    private function assertNothingWritten(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        self::assertSame(0, $this->countMonthly('leisure', 2026, 5), 'The line before the refused one was not written.');
        self::assertSame(0, $this->countEnvelopes('x'));
        $this->assertNothingPublished();
    }

    private function assertNothingPublished(): void
    {
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Envelope::class);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }
}
