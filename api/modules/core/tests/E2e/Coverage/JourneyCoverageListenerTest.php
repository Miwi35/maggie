<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e\Coverage;

use App\Kernel;
use Maggie\Core\E2e\Coverage\JourneyCoverageListener;
use Maggie\Core\E2e\Coverage\JourneyCoverageMerger;
use Maggie\Core\E2e\Coverage\JourneyCoverageWriter;
use Maggie\Core\E2e\Coverage\LineCollector;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;
use Symfony\Component\EventDispatcher\EventDispatcherInterface;
use Symfony\Component\Filesystem\Filesystem;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\Event\TerminateEvent;
use Symfony\Component\HttpKernel\HttpKernelInterface;
use Symfony\Component\HttpKernel\KernelEvents;

/**
 * Coverage per e2e journey, API side (spec « Sélection e2e par couverture », part A): a request
 * carrying `X-E2E-Journey` has its own executed lines written under that journey, in the raw
 * format of contract 2 — and nothing happens without E2E_COVERAGE, without the header, or
 * outside the e2e environment.
 */
final class JourneyCoverageListenerTest extends TestCase
{
    private const ROOT = '/var/www/api';
    private const CHAT = 'e2e/web/tests/chat.spec.ts';
    private const GROCERY = 'e2e/mobile/flows/20-grocery.yaml';

    private string $dir;
    private FakeCollector $collector;

    protected function setUp(): void
    {
        $this->dir = sys_get_temp_dir().'/e2e-coverage-'.bin2hex(random_bytes(4));
        $this->collector = new FakeCollector();
    }

    protected function tearDown(): void
    {
        (new Filesystem())->remove($this->dir);
    }

    public function testARequestOfAJourneyRecordsItsOwnLinesOnly(): void
    {
        $this->collector->lines = [
            self::ROOT.'/modules/grocery/src/Mcp/GroceryTools.php' => [30 => 1, 12 => 2, 13 => -1],
            self::ROOT.'/src/Kernel.php' => [5 => 1],
            self::ROOT.'/vendor/symfony/http-kernel/HttpKernel.php' => [10 => 1],
            self::ROOT.'/var/cache/e2e/ContainerX.php' => [3 => 1],
            self::ROOT.'/modules/grocery/tests/Mcp/GroceryToolsTest.php' => [8 => 1],
            self::ROOT.'/modules/core/src/Never.php' => [4 => -1],
            '/usr/local/lib/php/elsewhere.php' => [1 => 1],
        ];

        $this->handle($this->listener(), self::CHAT);

        self::assertTrue($this->collector->started);
        self::assertSame(
            [
                'journey' => self::CHAT,
                'files' => [
                    'api/modules/grocery/src/Mcp/GroceryTools.php' => [12, 30],
                    'api/src/Kernel.php' => [5],
                ],
            ],
            $this->onlyRequestFile(),
        );
    }

    public function testAWorkerAccumulatesItsRequestsInOneFilePerJourney(): void
    {
        $listener = $this->listener();
        $file = self::ROOT.'/modules/core/src/Controller/MeController.php';

        $this->collector->lines = [$file => [20 => 1, 21 => 1]];
        $this->handle($listener, self::CHAT);
        $this->collector->lines = [$file => [21 => 1, 9 => 1]];
        $this->handle($listener, self::CHAT);

        self::assertSame(['api/modules/core/src/Controller/MeController.php' => [9, 20, 21]], $this->onlyRequestFile()['files']);
    }

    /** @return iterable<string, array{bool, string|null}> */
    public static function nothingIsRecorded(): iterable
    {
        yield 'E2E_COVERAGE unset' => [false, self::CHAT];
        yield 'no header' => [true, null];
        yield 'a header that is not a repo path' => [true, '../../etc/passwd'];
    }

    #[DataProvider('nothingIsRecorded')]
    public function testNothingIsRecorded(bool $enabled, ?string $header): void
    {
        $this->collector->lines = [self::ROOT.'/src/Kernel.php' => [5 => 1]];

        $this->handle($this->listener($enabled), $header);

        self::assertFalse($this->collector->started);
        self::assertDirectoryDoesNotExist($this->dir);
    }

    public function testASubRequestDoesNotRestartTheCollection(): void
    {
        $listener = $this->listener();
        $kernel = $this->createStub(HttpKernelInterface::class);
        $request = Request::create('/api/me', server: ['HTTP_X_E2E_JOURNEY' => self::CHAT]);

        $listener->onRequest(new RequestEvent($kernel, $request, HttpKernelInterface::SUB_REQUEST));

        self::assertFalse($this->collector->started);
    }

    public function testAFailingWriteNeverFailsTheRequest(): void
    {
        // The coverage directory is a file: the write cannot happen.
        (new Filesystem())->dumpFile($this->dir, 'in the way');
        $this->collector->lines = [self::ROOT.'/src/Kernel.php' => [5 => 1]];

        $this->handle($this->listener(), self::CHAT);

        self::assertFileExists($this->dir);
        (new Filesystem())->remove($this->dir);
    }

    public function testTheMergerWritesOneRawFilePerJourney(): void
    {
        $writer = new JourneyCoverageWriter(self::ROOT, $this->dir);
        $file = self::ROOT.'/modules/core/src/Controller/MeController.php';
        $writer->record(self::CHAT, [$file => [3 => 1]], worker: 11);
        $writer->record(self::CHAT, [$file => [1 => 1, 3 => 1]], worker: 12);
        $writer->record(self::GROCERY, [$file => [7 => 1]], worker: 11);
        (new Filesystem())->dumpFile($this->dir.'/raw/api/renamed_journey.json', '{}');

        $count = (new JourneyCoverageMerger())->merge($this->dir.'/requests', $this->dir.'/raw/api');

        self::assertSame(2, $count);
        $raw = glob($this->dir.'/raw/api/*.json') ?: [];
        self::assertSame(
            ['e2e_mobile_flows_20-grocery_yaml.json', 'e2e_web_tests_chat_spec_ts.json'],
            array_map('basename', $raw),
        );
        self::assertSame(
            ['journey' => self::CHAT, 'files' => ['api/modules/core/src/Controller/MeController.php' => [1, 3]]],
            json_decode((string) file_get_contents($this->dir.'/raw/api/e2e_web_tests_chat_spec_ts.json'), true),
        );
    }

    /** @return iterable<string, array{string, bool}> */
    public static function environments(): iterable
    {
        yield 'prod' => ['prod', false];
        yield 'dev' => ['dev', false];
        yield 'test' => ['test', false];
        yield 'e2e' => ['e2e', true];
    }

    /**
     * Same guarantee as the test login (E2eSurfaceAbsenceTest): the listener is wired in `e2e`
     * and nowhere else, whatever E2E_COVERAGE says.
     */
    #[DataProvider('environments')]
    public function testTheListenerExistsInE2eOnly(string $environment, bool $expected): void
    {
        $_ENV['E2E_LOGIN_TOKEN'] = $_SERVER['E2E_LOGIN_TOKEN'] = 'token-for-the-absence-test';
        $kernel = new Kernel($environment, 'prod' !== $environment);
        $kernel->boot();
        $dispatcher = $kernel->getContainer()->get('event_dispatcher');
        self::assertInstanceOf(EventDispatcherInterface::class, $dispatcher);

        $classes = [];
        foreach ($dispatcher->getListeners(KernelEvents::TERMINATE) as $listener) {
            if (\is_array($listener) && \is_object($listener[0])) {
                $classes[] = $listener[0]::class;
            }
        }
        $kernel->shutdown();

        self::assertSame($expected, \in_array(JourneyCoverageListener::class, $classes, true));
    }

    private function listener(bool $enabled = true): JourneyCoverageListener
    {
        return new JourneyCoverageListener(
            $this->collector,
            new JourneyCoverageWriter(self::ROOT, $this->dir),
            new NullLogger(),
            $enabled,
        );
    }

    private function handle(JourneyCoverageListener $listener, ?string $journey): void
    {
        $kernel = $this->createStub(HttpKernelInterface::class);
        $server = null === $journey ? [] : ['HTTP_X_E2E_JOURNEY' => $journey];
        $request = Request::create('/api/me', server: $server);

        $listener->onRequest(new RequestEvent($kernel, $request, HttpKernelInterface::MAIN_REQUEST));
        $listener->onTerminate(new TerminateEvent($kernel, $request, new Response()));
    }

    /** @return array<mixed> */
    private function onlyRequestFile(): array
    {
        $files = glob($this->dir.'/requests/*/*.json') ?: [];
        self::assertCount(1, $files);
        self::assertStringContainsString('/requests/e2e_web_tests_chat_spec_ts/', $files[0]);

        $data = json_decode((string) file_get_contents($files[0]), true);
        self::assertIsArray($data);

        return $data;
    }
}

final class FakeCollector implements LineCollector
{
    public bool $started = false;

    /** @var array<string, array<int, int>> */
    public array $lines = [];

    public function start(): void
    {
        $this->started = true;
    }

    public function stop(): array
    {
        return $this->lines;
    }
}
