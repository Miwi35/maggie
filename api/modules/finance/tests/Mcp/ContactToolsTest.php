<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManageContactsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_contacts` (MAG-450): the same contacts and counterparties as the REST resources. */
class ContactToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const FIXTURES = __DIR__.'/../Api/fixtures/contacts.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$args): array
    {
        $tool = self::getContainer()->get(ManageContactsTool::class);

        return json_decode($tool($action, ...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function contactOf(string $transactionRef): ?string
    {
        $contact = $this->em()->find(Transaction::class, $this->getFixture($transactionRef)->getId())?->getContact();

        return null === $contact ? null : (string) $contact->getId();
    }

    public function testWithoutAUserTheToolRefuses(): void
    {
        $this->loadFixtures(self::FIXTURES);

        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->login();

        $data = $this->call('create', name: 'Julie', notes: 'Collègue');

        self::assertTrue($data['success']);
        self::assertSame('Julie', $data['contact']['name']);
        $julie = $this->em()->getRepository(Contact::class)->findOneBy(['name' => 'Julie']);
        self::assertSame('Collègue', $julie?->getNotes());
        $this->assertMercureUpdatePublished('/contacts/');
        $this->assertElasticsearchIndexDispatched(Contact::class);
    }

    public function testAnEmptyNameIsRefused(): void
    {
        $this->login();

        self::assertArrayHasKey('error', $this->call('create', name: '  '));
        self::assertArrayHasKey('error', $this->call('create'));
        self::assertCount(3, $this->em()->getRepository(Contact::class)->findAll());
    }

    public function testListAndSearchShowOnlyMyContactsWithTheirCounterparties(): void
    {
        $this->login();

        $contacts = $this->call('list')['contacts'];
        self::assertSame(['Anna', 'Tom'], array_column($contacts, 'name'));
        self::assertSame('VIR INST DE ANNA DUPONT', $contacts[0]['counterparties'][0]['label']);
        self::assertSame(1, $contacts[0]['counterparties'][0]['transactionCount']);

        self::assertSame(['Tom'], array_column($this->call('search', query: 'to')['contacts'], 'name'));
        self::assertSame([], $this->call('search', query: 'Paul')['contacts']);
    }

    public function testUpdateAndDelete(): void
    {
        $this->login();

        $data = $this->call('update', contactId: $this->id('tom'), name: 'Tom Martin', clear: ['notes']);
        self::assertSame('Tom Martin', $data['contact']['name']);
        self::assertNull($data['contact']['notes']);
        $this->assertMercureUpdatePublished('/contacts/'.$this->id('tom'));

        self::assertTrue($this->call('delete', contactId: $this->id('anna'))['success']);
        self::assertNull($this->em()->find(Contact::class, $this->getFixture('anna')->getId()));
        self::assertNull($this->contactOf('anna_1'));
        $this->assertElasticsearchDeleteDispatched();
    }

    public function testSomeoneElsesContactIsNotFound(): void
    {
        $this->login();

        self::assertStringContainsString('not found', $this->call('update', contactId: $this->id('others_contact'), name: 'X')['error']);
        self::assertStringContainsString('not found', $this->call('delete', contactId: $this->id('others_contact'))['error']);
        self::assertStringContainsString('not found', $this->call('link_counterparty', contactId: $this->id('others_contact'), label: 'VIR PAUL')['error']);
        self::assertStringContainsString('not found', $this->call('suggest_counterparties', contactId: $this->id('others_contact'))['error']);
    }

    public function testLinkingFromATransactionAttachesItsTransfersAndUnlinkingDetachesThem(): void
    {
        $this->login();

        $data = $this->call('link_counterparty', contactId: $this->id('tom'), transactionId: $this->id('tom_4'));

        self::assertTrue($data['success']);
        self::assertSame('VIR. DE TOM MARTIN', $data['counterparty']['label']);
        self::assertSame(4, $data['counterparty']['transactionCount']);
        foreach (['tom_1', 'tom_2', 'tom_3', 'tom_4'] as $ref) {
            self::assertSame($this->id('tom'), $this->contactOf($ref));
        }
        self::assertNull($this->contactOf('internal_tom'));
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('tom_1'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('tom_1'));

        // Linking it again to Tom changes nothing, to Anna is refused.
        self::assertSame($data['counterparty']['id'], $this->call('link_counterparty', contactId: $this->id('tom'), label: 'vir de tom martin')['counterparty']['id']);
        self::assertStringContainsString('already linked to Tom', $this->call('link_counterparty', contactId: $this->id('anna'), label: 'VIR. DE TOM MARTIN')['error']);

        self::assertTrue($this->call('unlink_counterparty', counterpartyId: $data['counterparty']['id'])['success']);
        self::assertNull($this->contactOf('tom_1'));
        self::assertCount(1, $this->em()->getRepository(ContactCounterparty::class)->findAll());
    }

    public function testLinkNeedsEitherALabelOrATransaction(): void
    {
        $this->login();

        self::assertArrayHasKey('error', $this->call('link_counterparty', contactId: $this->id('tom')));
        self::assertArrayHasKey('error', $this->call('link_counterparty', contactId: $this->id('tom'), label: 'X', transactionId: $this->id('tom_1')));
        self::assertArrayHasKey('error', $this->call('unlink_counterparty'));
    }

    public function testSuggestCounterparties(): void
    {
        $this->login();

        $data = $this->call('suggest_counterparties', contactId: $this->id('tom'));

        self::assertSame(['VIR. DE TOM MARTIN', 'PAYPAL *TOMMARTIN'], array_column($data['suggestions'], 'label'));
    }

    public function testAnUnknownActionIsNamed(): void
    {
        $this->login();

        self::assertStringContainsString('Unknown action', $this->call('merge')['error']);
    }
}
