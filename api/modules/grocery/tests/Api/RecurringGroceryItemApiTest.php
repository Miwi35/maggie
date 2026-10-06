<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\RecurringGroceryItem;
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
}
