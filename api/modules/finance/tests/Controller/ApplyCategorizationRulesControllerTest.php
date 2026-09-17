<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class ApplyCategorizationRulesControllerTest extends WebTestCase
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
        $this->client->request('POST', '/api/finance/apply-categorization-rules');

        self::assertResponseStatusCodeSame(401);
    }

    public function testApplyCategorizesTheMatchingHistory(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $food = $this->getFixture('food');
        $carrefour = $this->getFixture('uncategorized_carrefour');

        $this->client->request('POST', '/api/finance/apply-categorization-rules', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(1, $data['categorized']);
        self::assertSame(2, $data['scanned']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Transaction::class, $carrefour->getId());
        self::assertSame((string) $food->getId(), (string) $refreshed->getCategory()->getId());
        self::assertSame(CategorySource::Rule, $refreshed->getCategorySource());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testASecondPassChangesNothing(): void
    {
        $this->loadFixtures('categorization_rule.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('POST', '/api/finance/apply-categorization-rules', [], [], $this->authHeaders());
        $this->client->request('POST', '/api/finance/apply-categorization-rules', [], [], $this->authHeaders());

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(0, $data['categorized']);
        self::assertSame(1, $data['scanned']);
    }
}
