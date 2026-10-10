<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/transactions` leaves the rejected payments out unless
 * `transferKind` names a kind (MAG-375).
 */
final class TransactionRejectedFilterTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testTheListLeavesTheRejectedPairOut(): void
    {
        $this->world();

        self::assertEqualsCanonicalizing(['Courses', 'Virement vers Livret', 'Virement du Courant'], $this->labels('/api/transactions'));
    }

    public function testTheKindNamedByTheClientIsTheOnlyOneReturned(): void
    {
        $this->world();

        self::assertEqualsCanonicalizing(
            ['PRELEVEMENT EDF', 'REJET PRLV ELECTRICITE DE FRANCE'],
            $this->labels('/api/transactions?transferKind=rejected'),
        );
        self::assertEqualsCanonicalizing(
            ['Virement vers Livret', 'Virement du Courant'],
            $this->labels('/api/transactions?transferKind=internal'),
        );
        self::assertSame(['Courses'], $this->labels('/api/transactions?transferKind=none'));
    }

    public function testTheExclusionHoldsWhenTheListIsNarrowedToAnAccount(): void
    {
        $this->world();
        $account = $this->getFixture('checking');
        \assert($account instanceof Account);

        self::assertEqualsCanonicalizing(
            ['Courses', 'Virement vers Livret'],
            $this->labels('/api/transactions?account='.rawurlencode('/api/accounts/'.$account->getId())),
        );
    }

    private function world(): void
    {
        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $this->getFixture('test_user');
        $make = function (string $account, int $cents, string $day, string $label) use ($em, $user): Transaction {
            $transaction = (new Transaction())
                ->setUser($user)
                ->setAccount($this->getFixture($account))
                ->setAmountCents($cents)
                ->setCurrency('EUR')
                ->setBookedAt(new \DateTimeImmutable($day))
                ->setLabel($label)
                ->setStatus(TransactionStatus::Spent);
            $em->persist($transaction);

            return $transaction;
        };

        $make('checking', -4200, '2026-10-01', 'Courses');
        $make('checking', -20600, '2026-10-05', 'PRELEVEMENT EDF')
            ->markAsRejection($make('checking', 20600, '2026-10-06', 'REJET PRLV ELECTRICITE DE FRANCE'), TransferSource::Auto);
        $make('checking', -30000, '2026-10-02', 'Virement vers Livret')
            ->markAsInternalTransfer($make('savings', 30000, '2026-10-02', 'Virement du Courant'), TransferSource::Auto);

        $this->flushWithoutTransactionEffects($em);
    }

    /** @return list<string> */
    private function labels(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();

        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        return array_map(static fn (array $member): string => $member['label'], $body['member']);
    }
}
