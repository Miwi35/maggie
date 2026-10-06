<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * A reference to another user's object is refused, and nothing is stored.
 */
class ForeignReferenceApiTest extends WebTestCase
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
        $this->loadFixtures('foreign_references.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @param array<string, mixed> $body */
    private function send(string $method, string $uri, array $body): void
    {
        $this->client->request($method, $uri, [], [], array_merge([
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    private function iri(string $collection, string $fixture): string
    {
        return '/api/'.$collection.'/'.$this->getFixture($fixture)->getId();
    }

    private function assertRefused(): void
    {
        self::assertResponseStatusCodeSame(400);
        self::assertStringNotContainsString('Cadeaux secrets', (string) $this->client->getResponse()->getContent());
        $this->assertMercureUpdateCount(0);
    }

    private function rows(string $class): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository($class)->count([]);
    }

    private function reload(string $class, string $fixture): object
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository($class)->find($this->getFixture($fixture)->getId());
    }

    public function testCreateTransactionWithAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(Transaction::class);

        $this->send('POST', '/api/transactions', [
            'account' => $this->iri('accounts', 'checking'),
            'category' => $this->iri('categories', 'other_secret'),
            'amountCents' => -1599,
            'label' => 'Boulangerie',
            'bookedAt' => '2026-07-08',
        ]);

        $this->assertRefused();
        self::assertSame($before, $this->rows(Transaction::class));
    }

    public function testCreateTransactionWithAnotherUsersAccountIsRefused(): void
    {
        $before = $this->rows(Transaction::class);

        $this->send('POST', '/api/transactions', [
            'account' => $this->iri('accounts', 'other_checking'),
            'amountCents' => -1599,
            'label' => 'Boulangerie',
            'bookedAt' => '2026-07-08',
        ]);

        $this->assertRefused();
        self::assertSame($before, $this->rows(Transaction::class));
    }

    public function testPatchTransactionWithAnotherUsersCategoryIsRefused(): void
    {
        $this->send('PATCH', $this->iri('transactions', 'groceries'), [
            'category' => $this->iri('categories', 'other_secret'),
        ]);

        $this->assertRefused();
        $transaction = $this->reload(Transaction::class, 'groceries');
        self::assertSame('Alimentation', $transaction->getCategory()->getName());
    }

    public function testPatchTransactionWithAnotherUsersAccountIsRefused(): void
    {
        $this->send('PATCH', $this->iri('transactions', 'groceries'), [
            'account' => $this->iri('accounts', 'other_checking'),
        ]);

        $this->assertRefused();
        $transaction = $this->reload(Transaction::class, 'groceries');
        self::assertSame('Compte courant', $transaction->getAccount()->getName());
    }

    public function testCreateEnvelopeWithAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(Envelope::class);

        $this->send('POST', '/api/envelopes', [
            'category' => $this->iri('categories', 'other_secret'),
            'mode' => 'monthly',
            'amountCents' => 15000,
            'year' => 2026,
            'month' => 8,
        ]);

        $this->assertRefused();
        self::assertSame($before, $this->rows(Envelope::class));
    }

    public function testPatchEnvelopeWithAnotherUsersCategoryIsRefused(): void
    {
        $this->send('PATCH', $this->iri('envelopes', 'food_july'), [
            'category' => $this->iri('categories', 'other_secret'),
        ]);

        $this->assertRefused();
        self::assertSame('Alimentation', $this->reload(Envelope::class, 'food_july')->getCategory()->getName());
    }

    public function testCreateRuleWithAnotherUsersCategoryIsRefused(): void
    {
        $before = $this->rows(CategorizationRule::class);

        $this->send('POST', '/api/categorization_rules', [
            'category' => $this->iri('categories', 'other_secret'),
            'labelPattern' => 'BOULANGERIE',
        ]);

        $this->assertRefused();
        self::assertSame($before, $this->rows(CategorizationRule::class));
    }

    public function testPatchRuleWithAnotherUsersCategoryIsRefused(): void
    {
        $this->send('PATCH', $this->iri('categorization_rules', 'rule_carrefour'), [
            'category' => $this->iri('categories', 'other_secret'),
        ]);

        $this->assertRefused();
        self::assertSame('Alimentation', $this->reload(CategorizationRule::class, 'rule_carrefour')->getCategory()->getName());
    }

    public function testCreateCategoryWithAnotherUsersParentIsRefused(): void
    {
        $before = $this->rows(Category::class);

        $this->send('POST', '/api/categories', [
            'name' => 'Sous-catégorie',
            'parent' => $this->iri('categories', 'other_secret'),
        ]);

        $this->assertRefused();
        self::assertSame($before, $this->rows(Category::class));
    }

    public function testPatchCategoryWithAnotherUsersParentIsRefused(): void
    {
        $this->send('PATCH', $this->iri('categories', 'leisure'), [
            'parent' => $this->iri('categories', 'other_secret'),
        ]);

        $this->assertRefused();
        self::assertNull($this->reload(Category::class, 'leisure')->getParent());
    }
}
