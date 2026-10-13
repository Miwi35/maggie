<?php

namespace Maggie\Finance\Tests\Entity;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Projection\ProjectionMiddleware;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Message\UpdateTreasurySettingsCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

/**
 * The treasury settings reach the owner's open screens when they are created,
 * changed or removed, on `/api/treasury_settings/{id}` — the projection's
 * topic for the class, plural kept as is.
 */
class TreasurySettingsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/savings_advances.yaml');
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** Runs $work as the handler of a message the projection sees. */
    private function project(callable $work): void
    {
        $next = new class($work) implements MiddlewareInterface {
            public function __construct(private $work)
            {
            }

            public function handle(Envelope $envelope, StackInterface $stack): Envelope
            {
                ($this->work)();

                return $envelope->with(new HandledStamp(null, 'handler'));
            }
        };
        $stack = $this->createStub(StackInterface::class);
        $stack->method('next')->willReturn($next);

        $message = new UpdateTreasurySettingsCommand(userId: '', treasurySettingsId: '', salaryExpectedDay: null);
        self::getContainer()->get(ProjectionMiddleware::class)->handle(new Envelope($message, [new ReceivedStamp('sync')]), $stack);
    }

    private function topicOf(TreasurySettings $settings): string
    {
        return '/api/treasury_settings/'.$settings->getId();
    }

    public function testACreationIsPublishedToTheOwner(): void
    {
        $user = $this->em()->find(User::class, $this->getFixture('bare_user')->getId());
        $settings = (new TreasurySettings())->setUser($user)->setSalaryExpectedDay(26);

        $this->project(function () use ($settings) {
            $this->em()->persist($settings);
            $this->em()->flush();
        });

        $this->assertMercureUpdatePublished('/users/'.$user->getId().$this->topicOf($settings));
        self::assertSame(['@id' => $this->topicOf($settings), 'salaryExpectedDay' => 26, 'overdraftLimitCents' => 0], $this->mercurePayloadsOn($this->topicOf($settings))[0]);
        $this->assertNoElasticsearchIndexDispatched(TreasurySettings::class);
    }

    public function testAnUpdateIsPublished(): void
    {
        $settings = $this->em()->find(TreasurySettings::class, $this->getFixture('settings')->getId());

        $this->project(function () use ($settings) {
            $settings->setSalaryExpectedDay(1);
            $this->em()->flush();
        });

        self::assertSame(1, $this->mercurePayloadsOn($this->topicOf($settings))[0]['salaryExpectedDay']);
    }

    public function testAChangedOverdraftLimitIsPublished(): void
    {
        $settings = $this->em()->find(TreasurySettings::class, $this->getFixture('settings')->getId());

        $this->project(function () use ($settings) {
            $settings->setOverdraftLimitCents(30000);
            $this->em()->flush();
        });

        self::assertSame(['@id' => $this->topicOf($settings), 'overdraftLimitCents' => 30000], $this->mercurePayloadsOn($this->topicOf($settings))[0]);
    }

    public function testADeletionIsPublished(): void
    {
        $settings = $this->em()->find(TreasurySettings::class, $this->getFixture('settings')->getId());
        $topic = $this->topicOf($settings);

        $this->project(function () use ($settings) {
            $this->em()->remove($settings);
            $this->em()->flush();
        });

        $this->assertMercureDeletePublished($topic);
    }
}
