<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** `GET /api/accounts/{id}/incidents`: one line per rejected payment (MAG-375). */
final class AccountIncidentsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testWithoutATokenItIsRefused(): void
    {
        $this->loadFixtures('account_incidents.yaml');

        $this->client->request('GET', '/api/accounts/'.$this->account('checking')->getId().'/incidents');

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersOrUnknownAccountIsReportedMissing(): void
    {
        $this->loadFixtures('account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach ([(string) $this->account('stranger_account')->getId(), '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid'] as $id) {
            $this->get("/api/accounts/$id/incidents");
            self::assertResponseStatusCodeSame(404, $id);
        }
    }

    public function testAnAccountWithoutRejectionsHasNoIncident(): void
    {
        $this->loadFixtures('account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->line('checking', -4500, '2026-10-02', 'PRELEVEMENT NETFLIX');

        self::assertSame([], $this->incidents('checking'));
    }

    public function testOneLinePerRejectionNewestFirst(): void
    {
        $this->loadFixtures('account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $edfDebit = $this->line('checking', -20600, '2026-10-05', 'PRELEVEMENT EDF', 'EDF');
        $edfCredit = $this->line('checking', 20600, '2026-10-06', 'REJET PRLV ELECTRICITE DE FRANCE');
        $laureDebit = $this->line('checking', -20000, '2026-10-01', 'VIREMENT EMIS WEB Laure', 'Laure');
        $laureCredit = $this->line('checking', 20000, '2026-10-02', 'REJET VIREMENT WEB Laure');
        $this->line('checking', -999, '2026-10-03', 'PRELEVEMENT NETFLIX');
        $this->reject($edfDebit, $edfCredit);
        $this->reject($laureDebit, $laureCredit);

        self::assertSame([
            [
                'debitId' => (string) $edfDebit->getId(),
                'creditId' => (string) $edfCredit->getId(),
                'bookedAt' => '2026-10-05',
                'rejectedAt' => '2026-10-06',
                'counterpartyName' => 'EDF',
                'amountCents' => 20600,
                'kind' => 'direct_debit',
            ],
            [
                'debitId' => (string) $laureDebit->getId(),
                'creditId' => (string) $laureCredit->getId(),
                'bookedAt' => '2026-10-01',
                'rejectedAt' => '2026-10-02',
                'counterpartyName' => 'Laure',
                'amountCents' => 20000,
                'kind' => 'transfer',
            ],
        ], $this->incidents('checking'));
    }

    public function testTheKindFallsBackToTheCreditsWordingThenToDirectDebit(): void
    {
        $this->loadFixtures('account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $byCredit = $this->line('checking', -3000, '2026-10-03', 'Loyer octobre', 'Agence');
        $byCreditBack = $this->line('checking', 3000, '2026-10-04', 'REJET VIREMENT Agence');
        $bare = $this->line('checking', -1000, '2026-10-01', 'Cotisation club', 'Club');
        $bareBack = $this->line('checking', 1000, '2026-10-02', 'IMPAYE Club');
        $this->reject($byCredit, $byCreditBack);
        $this->reject($bare, $bareBack);

        self::assertSame(['transfer', 'direct_debit'], array_column($this->incidents('checking'), 'kind'));
    }

    public function testARejectedLineWithoutItsOtherLegStillShows(): void
    {
        $this->loadFixtures('account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $lonelyCredit = $this->line('checking', 9988, '2026-10-04', 'REJET PRLV EURO-ASSURANCE');
        $lonelyDebit = $this->line('checking', -5000, '2026-10-02', 'PRELEVEMENT ASSO', 'Asso');
        foreach ([$lonelyCredit, $lonelyDebit] as $line) {
            $line->setTransferKind(TransferKind::Rejected)->setTransferSource(TransferSource::Manual);
        }
        $this->flush();

        self::assertSame([
            [
                'debitId' => null,
                'creditId' => (string) $lonelyCredit->getId(),
                'bookedAt' => '2026-10-04',
                'rejectedAt' => '2026-10-04',
                'counterpartyName' => 'EURO-ASSURANCE',
                'amountCents' => 9988,
                'kind' => 'direct_debit',
            ],
            [
                'debitId' => (string) $lonelyDebit->getId(),
                'creditId' => null,
                'bookedAt' => '2026-10-02',
                'rejectedAt' => null,
                'counterpartyName' => 'Asso',
                'amountCents' => 5000,
                'kind' => 'direct_debit',
            ],
        ], $this->incidents('checking'));
    }

    public function testOnlyTheRequestedAccountsRejectionsAreListed(): void
    {
        $this->loadFixtures('account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $debit = $this->line('savings', -700, '2026-10-01', 'PRELEVEMENT LIVRET');
        $credit = $this->line('savings', 700, '2026-10-02', 'REJET PRLV LIVRET');
        $this->reject($debit, $credit);

        self::assertSame([], $this->incidents('checking'));
        self::assertCount(1, $this->incidents('savings'));
    }

    private function account(string $ref): Account
    {
        /** @var Account $account */
        $account = $this->getFixture($ref);

        return $account;
    }

    private function line(string $accountRef, int $cents, string $day, string $label, ?string $counterparty = null): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        /** @var User $user */
        $user = $this->getFixture('test_user');

        $transaction = (new Transaction())
            ->setUser($user)
            ->setAccount($this->account($accountRef))
            ->setAmountCents($cents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable($day))
            ->setLabel($label)
            ->setCounterpartyName($counterparty)
            ->setStatus(TransactionStatus::Spent);
        $em->persist($transaction);
        $this->flush();

        return $transaction;
    }

    private function reject(Transaction $debit, Transaction $credit): void
    {
        $debit->markAsRejection($credit, TransferSource::Auto);
        $this->flush();
    }

    private function flush(): void
    {
        $this->flushWithoutTransactionEffects(self::getContainer()->get('doctrine.orm.entity_manager'));
    }

    /** @return array<string, mixed> */
    private function get(string $path): array
    {
        $this->client->request('GET', $path, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<array<string, mixed>> */
    private function incidents(string $accountRef): array
    {
        $body = $this->get('/api/accounts/'.$this->account($accountRef)->getId().'/incidents');
        self::assertResponseIsSuccessful();

        return $body['incidents'];
    }
}
