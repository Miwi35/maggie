<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class TransactionApiTest extends WebTestCase
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

    public function testCreateTransactionRequiresAuthentication(): void
    {
        $this->loadFixtures('transaction.yaml');
        $account = $this->getFixture('checking');

        $this->client->request('POST', '/api/transactions', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            'account' => '/api/accounts/' . $account->getId(),
            'amountCents' => -1599,
            'label' => 'Boulangerie',
            'bookedAt' => '2026-07-08',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateTransactionPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('transaction.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $account = $this->getFixture('checking');
        $category = $this->getFixture('food');

        $this->client->request('POST', '/api/transactions', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'account' => '/api/accounts/' . $account->getId(),
            'category' => '/api/categories/' . $category->getId(),
            'amountCents' => -1599,
            'currency' => 'EUR',
            'label' => 'Boulangerie',
            'bookedAt' => '2026-07-08',
            'status' => 'spent',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(-1599, $data['amountCents']);
        self::assertSame('Boulangerie', $data['label']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(Transaction::class)->findAll());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testCreateTransactionValidationError(): void
    {
        $this->loadFixtures('transaction.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $account = $this->getFixture('checking');

        $this->client->request('POST', '/api/transactions', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // Missing required 'label'
            'account' => '/api/accounts/' . $account->getId(),
            'amountCents' => -100,
            'bookedAt' => '2026-07-08',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testDeleteTransactionRemovesAndPublishes(): void
    {
        $this->loadFixtures('transaction.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $transaction = $this->getFixture('groceries');
        $transactionId = $transaction->getId();

        $this->client->request('DELETE', '/api/transactions/' . $transactionId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Transaction::class, $transactionId));

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchDeleteDispatched('transactions');
    }
}
