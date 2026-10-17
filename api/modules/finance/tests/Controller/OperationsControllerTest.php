<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The preview and the catch-up of `/api/operations` (MAG-478): one door for
 * an operation with or without recurrence.
 */
class OperationsControllerTest extends WebTestCase
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
        $this->loadFixtures(dirname(__DIR__).'/Api/fixtures/operations.yaml');
    }

    public function testAnonymousCallsAreRefused(): void
    {
        $this->post('/api/finance/operations/preview', '{"labelPattern":"LIDL"}', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->post('/api/finance/operations/apply', '', authenticated: false);
        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->line('carrefour_line')->getCategory());
    }

    public function testABodyThatIsNotAnObjectIsRefused(): void
    {
        $this->post('/api/finance/operations/preview', '{not json');
        self::assertResponseStatusCodeSame(400);

        $this->post('/api/finance/operations/preview', '"LIDL"');
        self::assertResponseStatusCodeSame(400);
    }

    public function testAnOperationWithNothingToRecogniseByIsRefused(): void
    {
        $data = $this->post('/api/finance/operations/preview', '{"direction":"debit"}');

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $data);
    }

    public function testThePreviewOfAnOperationWithoutRecurrenceSaysWhatItWouldFile(): void
    {
        $data = $this->post('/api/finance/operations/preview', json_encode([
            'labelPattern' => 'LIDL',
            'categoryId' => (string) $this->getFixture('food')->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame(1, $data['total']);
        self::assertSame(1, $data['changeCount']);
        self::assertSame((string) $this->getFixture('lidl_line')->getId(), $data['matches'][0]['transactionId']);
        $this->assertMercureUpdateCount(0);
    }

    public function testThePreviewOfASeriesSaysWhatItWouldRecognise(): void
    {
        $data = $this->post('/api/finance/operations/preview', json_encode([
            'labelPattern' => 'CARREFOUR',
            'referenceAmountCents' => -4599,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame(1, $data['total']);
        self::assertArrayNotHasKey('changeCount', $data);
    }

    public function testTheCatchUpFilesTheHistoryPublishesAndIndexes(): void
    {
        $data = $this->post('/api/finance/operations/apply', '');

        self::assertResponseIsSuccessful();
        self::assertSame(1, $data['categorized']);
        self::assertSame(0, $data['attached']);
        $line = $this->line('carrefour_line');
        self::assertSame('Alimentation', $line->getCategory()?->getName());
        self::assertSame('rule', $line->getQualifiedBy());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $line->getId());
    }

    /** @return array<string, mixed> */
    private function post(string $uri, string $body, bool $authenticated = true): array
    {
        if ($authenticated) {
            $this->authenticateAsUser($this->getFixture('test_user'));
        }

        $headers = ['CONTENT_TYPE' => 'application/json'];
        $this->client->request('POST', $uri, [], [], $authenticated ? array_merge($headers, $this->authHeaders()) : $headers, $body);

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : (array) json_decode($content, true);
    }

    private function line(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $line = $em->find(Transaction::class, $this->getFixture($fixture)->getId());
        self::assertInstanceOf(Transaction::class, $line);

        return $line;
    }
}
