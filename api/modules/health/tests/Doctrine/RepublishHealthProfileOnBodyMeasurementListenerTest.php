<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Maggie\Health\Message\DeleteBodyMeasurementCommand;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Maggie\Health\Message\UpdateHealthProfileCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * A weighing recorded, set aside or removed moves the profile's weight, so the
 * profile is published again with its new figures (MAG-707) — whichever door
 * the weighing came through.
 */
class RepublishHealthProfileOnBodyMeasurementListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-30T09:00:00+00:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/health_profile.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get(MessageBusInterface::class);
    }

    private function userId(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** test_user: a man of 38, 178 cm — and 72 kg from the weighing of the 28th. */
    private function profile(): HealthProfile
    {
        $profile = $this->bus()->dispatch(new UpdateHealthProfileCommand(userId: $this->userId('test_user'), sex: 'male', birthYear: 1988, heightCm: 178))
            ->last(HandledStamp::class)?->getResult();
        \assert($profile instanceof HealthProfile);
        $this->resetMercure();

        return $profile;
    }

    /** @return array<string, mixed> */
    private function lastPayload(HealthProfile $profile): array
    {
        $payloads = $this->mercurePayloadsOn('/api/health-profile/'.$profile->getId());
        self::assertNotEmpty($payloads, 'The profile was not published again.');

        return end($payloads);
    }

    public function testANewWeighingRepublishesTheProfileWithItsWeight(): void
    {
        $profile = $this->profile();

        $this->bus()->dispatch(new CreateBodyMeasurementCommand(userId: $this->userId('test_user'), measuredAt: '2026-10-30T08:00:00+01:00', weightKg: 73.4));

        $payload = $this->lastPayload($profile);
        self::assertSame(73.4, $payload['weightKg']);
        self::assertSame(1990, $payload['needKcal']);
    }

    public function testSettingTheOnlyWeighingAsideLeavesTheProfileWithoutWeightNorFigures(): void
    {
        $profile = $this->profile();
        $measurement = $this->getFixture('weighing');
        \assert($measurement instanceof BodyMeasurement);

        $this->bus()->dispatch(new UpdateBodyMeasurementCommand(userId: $this->userId('test_user'), measurementId: (string) $measurement->getId(), excluded: true));

        $payload = $this->lastPayload($profile);
        self::assertNull($payload['weightKg']);
        self::assertNull($payload['bmrKcal']);
        self::assertNull($payload['needKcal']);
    }

    public function testARemovedWeighingRepublishesTheProfile(): void
    {
        $profile = $this->profile();
        $measurement = $this->getFixture('weighing');
        \assert($measurement instanceof BodyMeasurement);

        $this->bus()->dispatch(new DeleteBodyMeasurementCommand(userId: $this->userId('test_user'), measurementId: (string) $measurement->getId()));

        self::assertNull($this->lastPayload($profile)['weightKg']);
    }

    public function testAWeighingOfAUserWithoutProfilePublishesNoProfile(): void
    {
        $this->resetMercure();

        $this->bus()->dispatch(new CreateBodyMeasurementCommand(userId: $this->userId('test_user'), measuredAt: '2026-10-30T08:00:00+01:00', weightKg: 73.4));

        self::assertStringNotContainsString('/api/health-profile/', implode(' ', array_map(
            static fn ($update): string => implode(' ', $update->getTopics()),
            $this->getMercureHub()->getUpdates(),
        )));
    }
}
