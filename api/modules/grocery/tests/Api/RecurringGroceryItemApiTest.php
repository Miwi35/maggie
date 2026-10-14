<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\RecurringFrequency;
use Maggie\Grocery\Specification\IsRecurringGroceryItemDue;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class RecurringGroceryItemApiTest extends WebTestCase
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

    /** @param array<string, mixed> $payload */
    private function patch(RecurringGroceryItem $entity, array $payload): void
    {
        $this->client->request('PATCH', '/api/recurring_grocery_items/'.$entity->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(RecurringGroceryItem $entity): RecurringGroceryItem
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(RecurringGroceryItem::class)->find($entity->getId());
    }

    private function load(string $ref = 'item_full'): RecurringGroceryItem
    {
        $this->loadFixtures('RecurringGroceryItemApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture($ref);
    }

    public function testPatchRequiresAuthentication(): void
    {
        $entity = $this->load();

        $this->client->request('PATCH', '/api/recurring_grocery_items/'.$entity->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['quantity' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullQuantityAndUnitClearsThem(): void
    {
        $item = $this->load();

        $this->patch($item, ['quantity' => null, 'unit' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($item);
        self::assertNull($reloaded->getQuantity());
        self::assertNull($reloaded->getUnit());
        self::assertSame('Bananes bio', $reloaded->getCustomLabel(), 'Fields left out of the payload are untouched');
        self::assertNotNull($reloaded->getProduct());
        self::assertSame('weekly', $reloaded->getFrequency()->value);
        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testPatchSendingBackTheIdAsAnIriAsTheAdminDoesIsAccepted(): void
    {
        $item = $this->load();

        // react-admin's Hydra data provider replaces `id` by the IRI and adds `originId`.
        $this->patch($item, [
            'id' => '/api/recurring_grocery_items/'.$item->getId(),
            'originId' => (string) $item->getId(),
            'customLabel' => 'Bananes jaunes',
        ]);

        self::assertResponseIsSuccessful();
        self::assertSame('Bananes jaunes', $this->reload($item)->getCustomLabel());
    }

    public function testPatchWithNullCustomLabelKeepsTheProduct(): void
    {
        $item = $this->load();

        $this->patch($item, ['customLabel' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($item);
        self::assertNull($reloaded->getCustomLabel());
        self::assertSame('Bananes', $reloaded->getLabel());
    }

    public function testPatchWithoutQuantityKeepsIt(): void
    {
        $item = $this->load();

        $this->patch($item, ['frequency' => 'monthly']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($item);
        self::assertSame('monthly', $reloaded->getFrequency()->value);
        self::assertSame(2.5, $reloaded->getQuantity());
        self::assertSame('kg', $reloaded->getUnit()?->value);
    }

    /** @param array<string, mixed> $payload */
    private function post(array $payload, bool $authenticated = true): void
    {
        $this->client->request('POST', '/api/recurring_grocery_items', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function delete(RecurringGroceryItem $entity, bool $authenticated = true): void
    {
        $this->client->request('DELETE', '/api/recurring_grocery_items/'.$entity->getId(), [], [], $authenticated ? $this->authHeaders() : []);
    }

    public function testPostRequiresAuthentication(): void
    {
        $this->load();

        $this->post(['customLabel' => 'Lait', 'frequency' => 'weekly'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPostRefusesAnUnknownFrequency(): void
    {
        $this->load();

        $this->post(['customLabel' => 'Lait', 'frequency' => 'daily']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testPostCreatesAnItemForAProductAndAnnouncesItsFirstDueDay(): void
    {
        $this->load();
        $product = $this->getFixture('product');

        $this->post(['product' => '/api/products/'.$product->getId(), 'frequency' => 'biweekly']);

        self::assertResponseStatusCodeSame(201);
        $created = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('biweekly', $created['frequency']);
        self::assertSame('Bananes', $created['label']);
        self::assertSame(IsRecurringGroceryItemDue::today()->day()->format('Y-m-d'), $created['nextDueAt'], 'Never added: due at the next run');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(RecurringGroceryItem::class)->findOneBy(['frequency' => RecurringFrequency::Biweekly]);
        self::assertSame((string) $product->getId(), (string) $stored?->getProduct()?->getId());
        self::assertNull($stored?->getLastAddedAt());
        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testTheNextDueDayIsOnePeriodAfterTheLastAddition(): void
    {
        $item = $this->load();
        $today = IsRecurringGroceryItemDue::today()->day();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $item->setLastAddedAt($today);
        $em->flush();
        $em->clear();

        $this->client->request('GET', '/api/recurring_grocery_items/'.$item->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseIsSuccessful();
        $read = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame($today->add(new \DateInterval('P7D'))->format('Y-m-d'), $read['nextDueAt']);
    }

    public function testChangingTheFrequencyMovesTheNextDueDayAndPublishesIt(): void
    {
        $item = $this->load();
        $today = IsRecurringGroceryItemDue::today()->day();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $item->setLastAddedAt($today);
        $em->flush();
        $this->resetMercure();

        $this->patch($item, ['frequency' => 'monthly']);

        self::assertResponseIsSuccessful();
        $payloads = $this->mercurePayloadsOn('/api/recurring_grocery_items/'.$item->getId());
        self::assertCount(1, $payloads);
        self::assertSame('monthly', $payloads[0]['frequency']);
        self::assertSame($today->add(new \DateInterval('P1M'))->format('Y-m-d'), $payloads[0]['nextDueAt']);
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $item = $this->load();

        $this->delete($item, authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testDeleteRemovesTheItemAndTellsEveryScreen(): void
    {
        $item = $this->load();
        $id = $item->getId();

        $this->delete($item);

        self::assertResponseStatusCodeSame(204);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(RecurringGroceryItem::class, $id));
        $this->assertMercureDeletePublished('/api/recurring_grocery_items/'.$id);
        $this->assertElasticsearchDeleteDispatched();
    }
}
