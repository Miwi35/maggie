<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AccountApiTest extends WebTestCase
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

    public function testCreateAccountRequiresAuthentication(): void
    {
        $this->loadFixtures('user.yaml');

        $this->client->request('POST', '/api/accounts', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            'name' => 'Compte courant',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateAccountPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/accounts', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Compte courant',
            'type' => 'checking',
            'bank' => 'Crédit Agricole',
            'currency' => 'EUR',
            'balanceCents' => 125000,
            'isCushion' => false,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Compte courant', $data['name']);
        self::assertSame(125000, $data['balanceCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $accounts = $em->getRepository(Account::class)->findAll();
        self::assertCount(1, $accounts);
        self::assertSame('Compte courant', $accounts[0]->getName());

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testCreateAccountValidationError(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/accounts', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // Missing required 'name'
            'type' => 'checking',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testUpdateAccountPersistsAndPublishes(): void
    {
        $this->loadFixtures('account.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $account = $this->getFixture('checking');

        $this->client->request('PATCH', '/api/accounts/' . $account->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Compte principal',
            'balanceCents' => 90000,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Compte principal', $data['name']);
        self::assertSame(90000, $data['balanceCents']);

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $id, array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('PATCH', '/api/accounts/' . $id, [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testPatchAccountRequiresAuthentication(): void
    {
        $this->loadFixtures('account.yaml');
        $account = $this->getFixture('checking');

        $this->patch((string) $account->getId(), ['bank' => null], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullBankClearsIt(): void
    {
        $this->loadFixtures('account.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $account = $this->getFixture('checking');

        $this->patch((string) $account->getId(), ['bank' => null]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Account::class, $account->getId());
        self::assertNull($refreshed->getBank());
        self::assertSame('Compte courant', $refreshed->getName());
        self::assertSame(125000, $refreshed->getBalanceCents());

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testPatchWithoutBankLeavesItUntouched(): void
    {
        $this->loadFixtures('account.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $account = $this->getFixture('checking');

        $this->patch((string) $account->getId(), ['name' => 'Compte principal']);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('Crédit Agricole', $em->find(Account::class, $account->getId())->getBank());
    }

    public function testDeleteAccountRemovesAndPublishes(): void
    {
        $this->loadFixtures('account.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $account = $this->getFixture('checking');
        $accountId = $account->getId();

        $this->client->request('DELETE', '/api/accounts/' . $accountId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Account::class, $accountId));

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchDeleteDispatched('accounts');
    }
}
