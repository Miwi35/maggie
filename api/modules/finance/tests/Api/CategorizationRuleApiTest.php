<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class CategorizationRuleApiTest extends WebTestCase
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

    public function testCreateRuleRequiresAuthentication(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/categorization_rules', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            'labelPattern' => 'UGC',
            'category' => '/api/categories/' . $category->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateRulePersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/categorization_rules', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'labelPattern' => 'UGC',
            'matchType' => 'starts_with',
            'category' => '/api/categories/' . $category->getId(),
            'direction' => 'debit',
            'minAmountCents' => 500,
            'maxAmountCents' => 5000,
            'priority' => 20,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('UGC', $data['labelPattern']);
        self::assertSame('starts_with', $data['matchType']);
        self::assertSame(20, $data['priority']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(CategorizationRule::class)->findAll());

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testCreateRuleWithoutPatternIsRejected(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/categorization_rules', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // Missing required 'labelPattern'
            'category' => '/api/categories/' . $category->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testCreateRuleWithInvertedAmountRangeIsRejected(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('leisure');

        $this->client->request('POST', '/api/categorization_rules', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'labelPattern' => 'UGC',
            'category' => '/api/categories/' . $category->getId(),
            'minAmountCents' => 5000,
            'maxAmountCents' => 1000,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $id, array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('PATCH', '/api/categorization_rules/' . $id, [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testPatchRuleRequiresAuthentication(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        $rule = $this->getFixture('ranged_rule');

        $this->patch((string) $rule->getId(), ['maxAmountCents' => null], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullMaximumRemovesTheBound(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $rule = $this->getFixture('ranged_rule');

        $this->patch((string) $rule->getId(), ['maxAmountCents' => null]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(CategorizationRule::class, $rule->getId());
        self::assertNull($refreshed->getMaxAmountCents());
        self::assertSame(1000, $refreshed->getMinAmountCents());
        self::assertSame('CARREFOUR', $refreshed->getLabelPattern());

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testPatchWithoutBoundsLeavesThemUntouched(): void
    {
        $this->loadFixtures('categorization_rule_ranged.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $rule = $this->getFixture('ranged_rule');

        $this->patch((string) $rule->getId(), ['priority' => 20]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(CategorizationRule::class, $rule->getId());
        self::assertSame(20, $refreshed->getPriority());
        self::assertSame(1000, $refreshed->getMinAmountCents());
        self::assertSame(5000, $refreshed->getMaxAmountCents());
    }

    public function testDeleteRuleRemovesAndPublishes(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $rule = $this->getFixture('rule_carrefour');
        $ruleId = $rule->getId();

        $this->client->request('DELETE', '/api/categorization_rules/' . $ruleId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(CategorizationRule::class, $ruleId));

        $this->assertMercureUpdatePublished('/categorization_rules/');
        $this->assertElasticsearchDeleteDispatched('categorization_rules');
    }

    public function testATransactionPostedWithoutCategoryInheritsTheRule(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $account = $this->getFixture('checking');
        $food = $this->getFixture('food');

        $this->client->request('POST', '/api/transactions', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'account' => '/api/accounts/' . $account->getId(),
            'amountCents' => -3200,
            'currency' => 'EUR',
            'label' => 'CARREFOUR CITY 88',
            'bookedAt' => '2026-09-10',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertStringEndsWith((string) $food->getId(), $data['category']);
        self::assertSame('rule', $data['categorySource']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $persisted = $em->getRepository(Transaction::class)->findOneBy(['label' => 'CARREFOUR CITY 88']);
        self::assertSame(CategorySource::Rule, $persisted->getCategorySource());
    }
}
