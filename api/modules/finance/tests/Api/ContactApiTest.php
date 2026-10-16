<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Import\CsvStatementParser;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Contacts and their counterparties over REST (MAG-450): a label linked to a
 * contact attaches its transfers, past and future; removing it detaches them;
 * a transfer between the owner's own accounts is never attached.
 */
class ContactApiTest extends WebTestCase
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

    /** @param array<string, mixed>|null $body */
    private function call(string $method, string $uri, ?array $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function login(): void
    {
        $this->loadFixtures('contacts.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function contactOf(string $transactionRef): ?string
    {
        $transaction = $this->em()->find(Transaction::class, $this->getFixture($transactionRef)->getId());

        return null === $transaction?->getContact() ? null : (string) $transaction->getContact()->getId();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function linkToTom(string $label = 'VIR. DE TOM MARTIN'): void
    {
        $this->call('POST', '/api/contact_counterparties', [
            'contact' => '/api/contacts/'.$this->id('tom'),
            'label' => $label,
        ]);
    }

    // --- Contact --------------------------------------------------------------

    public function testCreatingAContactRequiresAuthentication(): void
    {
        $this->loadFixtures('contacts.yaml');

        $this->call('POST', '/api/contacts', ['name' => 'Julie'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreatingAContactPersistsPublishesAndIndexes(): void
    {
        $this->login();

        $this->call('POST', '/api/contacts', ['name' => '  Julie ', 'notes' => 'Collègue']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('Julie', $this->response()['name']);

        $julie = $this->em()->getRepository(Contact::class)->findOneBy(['name' => 'Julie']);
        self::assertNotNull($julie);
        self::assertSame('Collègue', $julie->getNotes());
        self::assertTrue($julie->getUser()->getId()->equals($this->getFixture('test_user')->getId()));

        $this->assertMercureUpdatePublished('/contacts/');
        $this->assertElasticsearchIndexDispatched(Contact::class);
    }

    public function testAContactWithoutNameIsRefused(): void
    {
        $this->login();

        $this->call('POST', '/api/contacts', ['name' => '   ']);

        self::assertResponseStatusCodeSame(422);
        self::assertCount(3, $this->em()->getRepository(Contact::class)->findAll());
    }

    public function testRenamingAContactPublishesIt(): void
    {
        $this->login();

        $this->call('PATCH', '/api/contacts/'.$this->id('tom'), ['name' => 'Tom Martin', 'notes' => null]);

        self::assertResponseStatusCodeSame(200);
        $tom = $this->em()->find(Contact::class, $this->getFixture('tom')->getId());
        self::assertSame('Tom Martin', $tom?->getName());
        self::assertNull($tom?->getNotes());
        $this->assertMercureUpdatePublished('/contacts/'.$this->id('tom'));
        $this->assertElasticsearchIndexDispatchedFor(Contact::class, $this->id('tom'));
    }

    public function testSomeoneElsesContactIsNotFound(): void
    {
        $this->login();

        $this->call('PATCH', '/api/contacts/'.$this->id('others_contact'), ['name' => 'Volé']);
        self::assertResponseStatusCodeSame(404);

        $this->call('DELETE', '/api/contacts/'.$this->id('others_contact'));
        self::assertResponseStatusCodeSame(404);

        $this->call('GET', '/api/finance/contacts/'.$this->id('others_contact').'/counterparty-suggestions');
        self::assertResponseStatusCodeSame(404);
    }

    public function testDeletingAContactDetachesTheirTransfersAndDropsTheirLabels(): void
    {
        $this->login();
        self::assertSame($this->id('anna'), $this->contactOf('anna_1'));

        $this->call('DELETE', '/api/contacts/'.$this->id('anna'));

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->contactOf('anna_1'));
        self::assertCount(0, $this->em()->getRepository(ContactCounterparty::class)->findAll());
        $this->assertMercureDeletePublished('/api/contacts/'.$this->id('anna'));
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('anna_1'));
    }

    // --- Counterparties -------------------------------------------------------

    public function testLinkingACounterpartyRequiresAuthentication(): void
    {
        $this->loadFixtures('contacts.yaml');

        $this->call('POST', '/api/contact_counterparties', [
            'contact' => '/api/contacts/'.$this->id('tom'),
            'label' => 'VIR. DE TOM MARTIN',
        ], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /** Acceptance: Tom's four transfers are attached, the internal transfer that says his name is not. */
    public function testLinkingALabelAttachesThePastTransfersButNeverAnInternalOne(): void
    {
        $this->login();

        $this->linkToTom();

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('VIR. DE TOM MARTIN', $data['label']);
        self::assertSame('vir de tom martin', $data['matchKey']);
        self::assertSame(4, $data['transactionCount']);
        self::assertSame('2026-09-05', $data['lastSeenOn']);

        foreach (['tom_1', 'tom_2', 'tom_3', 'tom_4'] as $ref) {
            self::assertSame($this->id('tom'), $this->contactOf($ref), $ref);
        }
        self::assertNull($this->contactOf('internal_tom'));
        self::assertNull($this->contactOf('paypal_1'));

        $stored = $this->em()->getRepository(ContactCounterparty::class)->findOneBy(['matchKey' => 'vir de tom martin']);
        self::assertSame($this->id('tom'), (string) $stored?->getContact()->getId());

        $this->assertMercureUpdatePublished('/contact_counterparties/');
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('tom_1'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('tom_4'));
    }

    /** Acceptance: the next transfer, typed in or imported, is attached as it is written. */
    public function testTheNextTransferIsAttachedWhenWrittenOrImported(): void
    {
        $this->login();
        $this->linkToTom();
        self::assertResponseStatusCodeSame(201);

        $this->call('POST', '/api/transactions', [
            'account' => '/api/accounts/'.$this->id('checking'),
            'amountCents' => 40000,
            'currency' => 'EUR',
            'label' => 'VIR. DE TOM MARTIN',
            'bookedAt' => '2026-10-05',
            'status' => 'spent',
        ]);
        self::assertResponseStatusCodeSame(201);
        self::assertSame('/api/contacts/'.$this->id('tom'), $this->response()['contact']);

        $parser = self::getContainer()->get(CsvStatementParser::class);
        self::getContainer()->get(ImportStatement::class)->execute(
            $this->em()->find(\Maggie\Finance\Entity\Account::class, $this->getFixture('checking')->getId()),
            $parser->parse("Date;Libellé;Montant\n05/11/2026;VIR. DE TOM MARTIN;400,00\n")['rows'],
        );

        $imported = $this->em()->getRepository(Transaction::class)->findBy(['label' => 'VIR. DE TOM MARTIN', 'bookedAt' => new \DateTimeImmutable('2026-11-05')]);
        self::assertCount(1, $imported);
        self::assertSame($this->id('tom'), (string) $imported[0]->getContact()?->getId());
    }

    public function testRemovingTheLabelDetachesItsTransfers(): void
    {
        $this->login();
        $counterpartyId = $this->id('anna_label');
        self::assertSame($this->id('anna'), $this->contactOf('anna_1'));

        $this->call('DELETE', '/api/contact_counterparties/'.$counterpartyId);

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->contactOf('anna_1'));
        self::assertNull($this->em()->find(ContactCounterparty::class, $this->getFixture('anna_label')->getId()));
        $this->assertMercureDeletePublished('/api/contact_counterparties/'.$counterpartyId);
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('anna_1'));
    }

    public function testALabelAlreadyLinkedToAnotherContactIsRefused(): void
    {
        $this->login();

        $this->linkToTom('Vir inst de Anna Dupont');

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Anna', (string) $this->client->getResponse()->getContent());
        self::assertCount(1, $this->em()->getRepository(ContactCounterparty::class)->findAll());
        self::assertSame($this->id('anna'), $this->contactOf('anna_1'));
    }

    public function testALabelWithoutLettersIsRefused(): void
    {
        $this->login();

        $this->linkToTom('***');

        self::assertResponseStatusCodeSame(400);
    }

    public function testALabelCannotBeLinkedToSomeoneElsesContact(): void
    {
        $this->login();

        $this->call('POST', '/api/contact_counterparties', [
            'contact' => '/api/contacts/'.$this->id('others_contact'),
            'label' => 'VIR. DE PAUL',
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertCount(1, $this->em()->getRepository(ContactCounterparty::class)->findAll());
    }

    public function testTheCounterpartiesOfAContactCarryTheirTransfers(): void
    {
        $this->login();

        $this->call('GET', '/api/contact_counterparties?contact='.$this->id('anna'));

        self::assertResponseStatusCodeSame(200);
        $members = $this->response()['member'];
        self::assertCount(1, $members);
        self::assertSame('VIR INST DE ANNA DUPONT', $members[0]['label']);
        self::assertSame(1, $members[0]['transactionCount']);
        self::assertSame('2026-09-10', $members[0]['lastSeenOn']);

        $this->call('GET', '/api/contact_counterparties?contact='.$this->id('tom'));
        self::assertCount(0, $this->response()['member']);
    }

    // --- Suggestions ----------------------------------------------------------

    public function testSuggestionsRequireAuthentication(): void
    {
        $this->loadFixtures('contacts.yaml');

        $this->call('GET', '/api/finance/contacts/'.$this->id('tom').'/counterparty-suggestions', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /** Acceptance: « PAYPAL *TOMMARTIN » is suggested on Tom's page, and adds in one gesture. */
    public function testTheLabelsThatNameTheContactAreSuggestedMostFrequentFirst(): void
    {
        $this->login();

        $this->call('GET', '/api/finance/contacts/'.$this->id('tom').'/counterparty-suggestions');

        self::assertResponseStatusCodeSame(200);
        self::assertSame([
            ['label' => 'VIR. DE TOM MARTIN', 'transactionCount' => 4, 'lastSeenOn' => '2026-09-05'],
            ['label' => 'PAYPAL *TOMMARTIN', 'transactionCount' => 2, 'lastSeenOn' => '2026-02-12'],
        ], $this->response());

        $this->linkToTom('PAYPAL *TOMMARTIN');
        self::assertResponseStatusCodeSame(201);
        self::assertSame($this->id('tom'), $this->contactOf('paypal_2'));

        $this->call('GET', '/api/finance/contacts/'.$this->id('tom').'/counterparty-suggestions');
        self::assertSame(['VIR. DE TOM MARTIN'], array_column($this->response(), 'label'));
    }

    // --- « À trier » ----------------------------------------------------------

    public function testTheToSortPileAsksForTheContactOfAnUnclaimedTransfer(): void
    {
        $this->login();

        $this->call('GET', '/api/finance/transactions/to-sort?limit=50');

        self::assertResponseStatusCodeSame(200);
        $byId = array_column($this->response()['transactions'], null, 'id');

        $julie = $byId[$this->id('julie_1')];
        self::assertTrue($julie['asksForContact']);
        self::assertNull($julie['contact']);
        self::assertSame('VIR INST DE JULIE DURAND', $julie['counterpartyLabel']);

        self::assertSame('VIR. DE TOM MARTIN', $byId[$this->id('tom_4')]['counterpartyLabel']);

        $anna = $byId[$this->id('anna_1')];
        self::assertFalse($anna['asksForContact']);
        self::assertSame(['id' => $this->id('anna'), 'name' => 'Anna'], $anna['contact']);

        self::assertFalse($byId[$this->id('groceries')]['asksForContact']);
        self::assertArrayNotHasKey($this->id('internal_tom'), $byId);
    }

    /** Acceptance: from « À trier », « + Créer « Julie » » creates Julie and attaches the transfer. */
    public function testCreatingAContactFromTheToSortPileAttachesTheTransfer(): void
    {
        $this->login();

        $this->call('POST', '/api/contacts', ['name' => 'Julie']);
        self::assertResponseStatusCodeSame(201);
        $julieIri = $this->response()['@id'];

        $this->call('POST', '/api/contact_counterparties', ['contact' => $julieIri, 'label' => 'VIR INST DE JULIE DURAND']);
        self::assertResponseStatusCodeSame(201);

        $julie = $this->em()->getRepository(Contact::class)->findOneBy(['name' => 'Julie']);
        self::assertSame((string) $julie?->getId(), $this->contactOf('julie_1'));
    }
}
