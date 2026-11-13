<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** `GET …/link-candidates` and `POST …/link`: a hand-typed account linked from its page (MAG-823). */
final class AccountLinkControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->loadFixtures('account_link.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCandidatesListOnlyLinkedAccountsOfTheSameCurrency(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->request('GET', '/api/accounts/'.$this->id('manual').'/link-candidates');

        self::assertResponseIsSuccessful();
        self::assertSame([[
            'id' => $this->id('bank_eur'),
            'name' => 'Compte courant',
            'bank' => 'N26',
            'type' => 'checking',
            'currency' => 'EUR',
            'balanceCents' => 115520,
        ]], $body['candidates']);
    }

    public function testCandidatesAreEmptyForALinkedAccount(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->request('GET', '/api/accounts/'.$this->id('bank_eur').'/link-candidates');

        self::assertResponseIsSuccessful();
        self::assertSame([], $body['candidates']);
    }

    public function testCandidatesRequireAToken(): void
    {
        $this->client->request('GET', '/api/accounts/'.$this->id('manual').'/link-candidates');

        self::assertResponseStatusCodeSame(401);
    }

    public function testCandidatesOfAnotherUsersAccountAre404(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach ([$this->id('stranger_account'), '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid'] as $id) {
            $this->request('GET', "/api/accounts/$id/link-candidates");
            self::assertResponseStatusCodeSame(404, $id);
        }
    }

    public function testLinkWithoutConfirmWritesNothing(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->movement('bank_eur', 'CB CARREFOUR');
        $this->movement('bank_eur', 'CB BOULANGERIE');

        $body = $this->request('POST', '/api/accounts/'.$this->id('manual').'/link', ['bankAccountId' => $this->id('bank_eur')]);

        self::assertResponseIsSuccessful();
        self::assertSame(['kept' => $this->id('manual'), 'moved' => 2, 'dropped' => 0], $body);
        $em = $this->em();
        $em->clear();
        self::assertNotNull($em->find(Account::class, $this->id('bank_eur')));
        self::assertNull($em->find(Account::class, $this->id('manual'))->getBankConnection());
        $this->assertMercureUpdateCount(0);
    }

    public function testLinkWithConfirmFoldsTheBankAccount(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $keptId = $this->id('manual');
        $bankId = $this->id('bank_eur');
        $this->movement('bank_eur', 'CB CARREFOUR');

        $body = $this->request('POST', "/api/accounts/$keptId/link", ['bankAccountId' => $bankId, 'confirm' => true]);

        self::assertResponseIsSuccessful();
        self::assertSame(['kept' => $keptId, 'moved' => 1, 'dropped' => 0, 'linked' => true], $body);
        $em = $this->em();
        $em->clear();
        self::assertNull($em->find(Account::class, $bankId));
        $kept = $em->find(Account::class, $keptId);
        self::assertNotNull($kept->getBankConnection());
        self::assertSame('Compte à la main', $kept->getName());
        self::assertSame(115520, $kept->getBalanceCents());
        $this->assertMercureUpdatePublished('/api/accounts/'.$keptId);
        $this->assertElasticsearchIndexDispatched();
    }

    public function testLinkRefusalIs400AndWritesNothing(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->request('POST', '/api/accounts/'.$this->id('manual').'/link', ['bankAccountId' => $this->id('bank_usd'), 'confirm' => true]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('currency', $body['error']);
        $em = $this->em();
        $em->clear();
        self::assertNotNull($em->find(Account::class, $this->id('bank_usd')));
        self::assertNull($em->find(Account::class, $this->id('manual'))->getBankConnection());
    }

    public function testLinkWithoutBankAccountIdIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->request('POST', '/api/accounts/'.$this->id('manual').'/link', ['confirm' => true]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testLinkToAnotherUsersAccountIs404(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->request('POST', '/api/accounts/'.$this->id('manual').'/link', ['bankAccountId' => $this->id('stranger_account'), 'confirm' => true]);

        self::assertResponseStatusCodeSame(404);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        /** @var Account $account */
        $account = $this->getFixture($ref);

        return (string) $account->getId();
    }

    private function movement(string $accountRef, string $label): void
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');
        /** @var Account $account */
        $account = $this->getFixture($accountRef);
        $transaction = (new Transaction())
            ->setUser($user)
            ->setAccount($account)
            ->setAmountCents(-1500)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable('2026-10-02'))
            ->setLabel($label)
            ->setStatus(TransactionStatus::Spent);
        $this->em()->persist($transaction);
        $this->em()->flush();
    }

    /**
     * @param array<string, mixed> $payload
     *
     * @return array<string, mixed>
     */
    private function request(string $method, string $path, array $payload = []): array
    {
        $this->client->request($method, $path, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], [] === $payload ? null : json_encode($payload, JSON_THROW_ON_ERROR));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR) ?? [];
    }
}
