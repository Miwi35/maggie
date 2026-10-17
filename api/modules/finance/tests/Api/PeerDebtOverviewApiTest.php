<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * « Créances et dettes » (MAG-455): what is left per person (net, and the
 * compensation when they owe each other) and per object, with the transfers
 * that gave each back. World: `peer_debts_overview.yaml`.
 */
class PeerDebtOverviewApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    private function get(string $uri, bool $authenticated = true): void
    {
        $this->client->request('GET', $uri, [], [], $authenticated ? $this->authHeaders() : []);
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function login(): void
    {
        $this->loadFixtures('peer_debts_overview.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return list<array<string, mixed>> */
    private function debts(string $query = ''): array
    {
        $this->get('/api/finance/peer-debts'.$query);
        self::assertResponseIsSuccessful();

        return $this->response()['peerDebts'];
    }

    public function testTheListAndTheSummaryRequireAuthentication(): void
    {
        $this->get('/api/finance/peer-debts', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->get('/api/finance/peer-debts/summary', authenticated: false);
        self::assertResponseStatusCodeSame(401);
    }

    public function testTheSummaryGivesTheNetPerPersonAndSignalsTheCompensation(): void
    {
        $this->login();

        $this->get('/api/finance/peer-debts/summary');

        self::assertResponseIsSuccessful();
        // 150 € left on the gîte for Julie + 200 € for Tom; 280 € of petrol to Tom; Lucas gave all back. The biggest net first.
        self::assertSame([
            'owedToMeCents' => 35000,
            'iOweCents' => 28000,
            'byContact' => [
                ['contactId' => $this->id('julie'), 'name' => 'Julie', 'netCents' => 15000, 'compensates' => false],
                ['contactId' => $this->id('tom'), 'name' => 'Tom', 'netCents' => -8000, 'compensates' => true],
            ],
        ], $this->response());
    }

    public function testTheSummaryIsTheOwnersOnly(): void
    {
        $this->loadFixtures('peer_debts_overview.yaml');
        $this->authenticateAsUser($this->getFixture('other_user'));

        $this->get('/api/finance/peer-debts/summary');

        self::assertSame([
            'owedToMeCents' => 4000,
            'iOweCents' => 0,
            'byContact' => [['contactId' => $this->id('others_contact'), 'name' => 'Paul', 'netCents' => 4000, 'compensates' => false]],
        ], $this->response());
    }

    public function testTheListIsTheOwnersNewestFirstWithSharesAndHistory(): void
    {
        $this->login();

        $debts = $this->debts();

        self::assertSame(['Déjeuner', 'Essence du road trip', 'Gîte de Crozon'], array_column($debts, 'object'));
        $gite = $debts[2];
        self::assertSame('owed_to_me', $gite['direction']);
        self::assertSame(40000, $gite['amountCents']);
        self::assertSame(35000, $gite['remainingCents']);
        self::assertSame('open', $gite['status']);
        self::assertSame(['Julie' => 15000, 'Tom' => 20000], array_column($gite['shares'], 'remainingCents', 'contactName'));
        self::assertSame([[
            'transactionId' => $this->id('julie_in'),
            'bookedAt' => '2026-07-20',
            'label' => 'VIR DE JULIE ROUX',
            'amountCents' => 5000,
            'contactId' => $this->id('julie'),
            'contactName' => 'Julie',
        ]], $gite['history']);
        self::assertSame([], $debts[1]['history']);
    }

    /** @return iterable<string, array{0: string, 1: list<string>}> */
    public static function filters(): iterable
    {
        yield 'open ones' => ['?status=open', ['Essence du road trip', 'Gîte de Crozon']];
        yield 'settled ones' => ['?status=settled', ['Déjeuner']];
        yield 'what I owe' => ['?direction=i_owe', ['Essence du road trip']];
        yield 'what is owed to me, open' => ['?direction=owed_to_me&status=open', ['Gîte de Crozon']];
    }

    /** @param list<string> $objects */
    #[\PHPUnit\Framework\Attributes\DataProvider('filters')]
    public function testTheListIsFilteredByStatusAndDirection(string $query, array $objects): void
    {
        $this->login();

        self::assertSame($objects, array_column($this->debts($query), 'object'));
    }

    public function testTheListIsFilteredByPerson(): void
    {
        $this->login();

        self::assertSame(['Essence du road trip', 'Gîte de Crozon'], array_column($this->debts('?contact='.$this->id('tom')), 'object'));
        self::assertSame(['Gîte de Crozon'], array_column($this->debts('?contact='.$this->id('julie')), 'object'));
    }

    /** @return iterable<string, array{0: string}> */
    public static function badFilters(): iterable
    {
        yield 'a status that does not exist' => ['?status=late'];
        yield 'a direction that does not exist' => ['?direction=both'];
        yield 'a contact that is no id' => ['?contact=tom'];
        yield 'a list instead of a status' => ['?status[]=open'];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('badFilters')]
    public function testAnInvalidFilterIsRefused(string $query): void
    {
        $this->login();

        $this->get('/api/finance/peer-debts'.$query);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $this->response());
    }

    public function testAnotherUsersContactIsNotFound(): void
    {
        $this->login();

        $this->get('/api/finance/peer-debts?contact='.$this->id('others_contact'));

        self::assertResponseStatusCodeSame(404);
    }
}
