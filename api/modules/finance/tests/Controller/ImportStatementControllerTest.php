<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\HttpFoundation\File\UploadedFile;

class ImportStatementControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const ENDPOINT = '/api/finance/import-statement';

    /**
     * Three lines: the first is already on the account, the second and third
     * are new, and `CARREFOUR CITY` is what the seeded rule claims.
     */
    private const STATEMENT = <<<'CSV'
        Date;Libellé;Montant
        01/09/2026;CARREFOUR MARKET 4412;-45,99
        07/09/2026;CARREFOUR CITY;-8,10
        08/09/2026;BOULANGERIE DU COIN;-6,40
        CSV;

    private KernelBrowser $client;

    /** @var list<string> */
    private array $tempFiles = [];

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        foreach ($this->tempFiles as $path) {
            if (is_file($path)) {
                unlink($path);
            }
        }
        $this->tempFiles = [];

        parent::tearDown();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAMissingFileIsRefused(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request(
            'POST',
            self::ENDPOINT,
            ['account' => (string) $this->account()->getId()],
            [],
            $this->authHeaders(),
        );

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Joignez le fichier', $this->body()['error']);
    }

    public function testAMissingAccountIsRefused(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->post([], self::STATEMENT);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Choisissez le compte', $this->body()['error']);
    }

    public function testAnIdentifierThatIsNotAnAccountIsRefused(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->post(['account' => 'pas-un-ulid'], self::STATEMENT);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('identifiant de compte', $this->body()['error']);
    }

    public function testAnUploadCutShortAsksForAnotherTry(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->post(
            ['account' => (string) $this->account()->getId(), 'confirm' => '1'],
            self::STATEMENT,
            \UPLOAD_ERR_PARTIAL,
        );

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString("n'est pas arrivé en entier", $this->body()['error']);
    }

    public function testAFilePhpItselfTurnedAwayIsNotOfferedARetry(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->post(
            ['account' => (string) $this->account()->getId(), 'confirm' => '1'],
            self::STATEMENT,
            \UPLOAD_ERR_INI_SIZE,
        );

        self::assertResponseStatusCodeSame(400);
        // "Retry" would send the owner round for ever on a file that cannot fit.
        self::assertStringContainsString('trop volumineux', $this->body()['error']);
    }

    public function testAFileTooLargeToBeAStatementIsRefusedBeforeBeingParsed(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        // Over 2 MB of perfectly valid lines: the size is the refusal, not the
        // content. Nothing reaches the parser and nothing reaches the database.
        $before = $this->countTransactions();
        $padding = str_repeat("09/09/2026;LIGNE DE REMPLISSAGE;-1,00\n", 70_000);

        $this->post(
            ['account' => (string) $this->account()->getId(), 'confirm' => '1'],
            "Date;Libellé;Montant\n".$padding,
        );

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('dépasse 2 Mo', $this->body()['error']);
        self::assertSame($before, $this->countTransactions());
    }

    public function testSomeoneElsesAccountIsNotFound(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        /** @var Account $stranger */
        $stranger = $this->getFixture('other_checking');
        $before = $this->countTransactions();

        $this->post(['account' => (string) $stranger->getId()], self::STATEMENT);

        // Not found rather than forbidden: the answer must not confirm that
        // this account exists.
        self::assertResponseStatusCodeSame(404);
        self::assertSame($before, $this->countTransactions());
    }

    public function testAFileWithNoMovementIsRefusedWithWhatWentWrong(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->post(
            ['account' => (string) $this->account()->getId()],
            "Relevé du mois\nrien d'exploitable ici\n",
        );

        self::assertResponseStatusCodeSame(400);
        $body = $this->body();
        self::assertStringContainsString('Aucun mouvement', $body['error']);
        self::assertNotEmpty($body['errors'], 'the parser says what it could not read');
    }

    public function testARehearsalReportsEveryLineWithoutWritingAnything(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $before = $this->countTransactions();

        $this->post(['account' => (string) $this->account()->getId()], self::STATEMENT);

        self::assertResponseIsSuccessful();
        $body = $this->body();

        self::assertTrue($body['dryRun']);
        self::assertSame('Compte courant', $body['account']['name']);
        self::assertSame(3, $body['rowsRead']);
        self::assertSame(2, $body['imported']);
        self::assertSame(1, $body['skipped']);
        self::assertSame(1, $body['categorized']);
        self::assertSame('2026-09-07', $body['first']);
        self::assertSame('2026-09-08', $body['last']);
        self::assertSame(-1450, $body['totalCents']);

        $byLabel = array_column($body['rows'], null, 'label');
        self::assertTrue($byLabel['CARREFOUR MARKET 4412']['duplicate']);
        self::assertFalse($byLabel['CARREFOUR CITY']['duplicate']);
        self::assertSame('Alimentation', $byLabel['CARREFOUR CITY']['categoryName']);
        self::assertNull($byLabel['BOULANGERIE DU COIN']['categoryName']);

        // The whole point of the rehearsal.
        self::assertSame($before, $this->countTransactions());
        self::assertSame([], $this->getMercureHub()->getUpdates());
    }

    public function testConfirmingImportsTheMovementsAndTellsTheRestOfTheSystem(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $before = $this->countTransactions();

        $this->post(
            ['account' => (string) $this->account()->getId(), 'confirm' => '1'],
            self::STATEMENT,
        );

        self::assertResponseIsSuccessful();
        $body = $this->body();
        self::assertFalse($body['dryRun']);
        self::assertSame(2, $body['imported']);

        self::assertSame($before + 2, $this->countTransactions());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(Transaction::class)->findOneBy(['label' => 'CARREFOUR CITY']);
        self::assertNotNull($stored);
        self::assertSame(-810, $stored->getAmountCents());
        self::assertSame('2026-09-07', $stored->getBookedAt()->format('Y-m-d'));
        self::assertSame('Alimentation', $stored->getCategory()?->getName());
        self::assertSame(CategorySource::Rule, $stored->getCategorySource());
        self::assertSame(
            (string) $this->account()->getId(),
            (string) $stored->getAccount()->getId(),
        );

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testImportingTheSameFileAgainBringsNothingNew(): void
    {
        $this->loadFixtures('statement_import.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $accountId = (string) $this->account()->getId();
        $this->post(['account' => $accountId, 'confirm' => '1'], self::STATEMENT);
        $afterFirst = $this->countTransactions();

        $this->post(['account' => $accountId, 'confirm' => '1'], self::STATEMENT);

        self::assertResponseIsSuccessful();
        $body = $this->body();
        self::assertSame(0, $body['imported']);
        self::assertSame(3, $body['skipped']);
        self::assertSame($afterFirst, $this->countTransactions());
    }

    /**
     * @param array<string, string> $parameters
     * @param int                   $error      an `UPLOAD_ERR_*` PHP would have reported
     */
    private function post(array $parameters, string $csv, int $error = \UPLOAD_ERR_OK): void
    {
        $path = (string) tempnam(sys_get_temp_dir(), 'statement');
        file_put_contents($path, $csv);
        $this->tempFiles[] = $path;

        $this->client->request(
            'POST',
            self::ENDPOINT,
            $parameters,
            ['file' => new UploadedFile($path, 'releve.csv', 'text/csv', $error, true)],
            $this->authHeaders(),
        );
    }

    /** @return array<string, mixed> */
    private function body(): array
    {
        return json_decode(
            (string) $this->client->getResponse()->getContent(),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    private function account(): Account
    {
        /** @var Account $account */
        $account = $this->getFixture('checking');

        return $account;
    }

    private function countTransactions(): int
    {
        return \count(
            self::getContainer()->get('doctrine.orm.entity_manager')
                ->getRepository(Transaction::class)
                ->findAll(),
        );
    }
}
