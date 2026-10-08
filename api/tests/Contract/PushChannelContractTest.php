<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Push\PushMessageFactory;
use PHPUnit\Framework\TestCase;

/**
 * The Android channels the push names are the channels the app creates.
 *
 * A message sent to a channel the phone never created is shown without sound or
 * importance (or dropped on Android 8+), and nothing fails on either side. The
 * app's `PushChannelsContractTest` reads contract/push-channels.json, written
 * here from the factory's own constants.
 */
final class PushChannelContractTest extends TestCase
{
    use ContractSnapshotTrait;

    public function testThePushChannelsArePublished(): void
    {
        $this->assertMatchesContract(
            'push-channels.json',
            self::channels(),
            'The Android channels of the push changed. The app creates each one on the phone; a channel it does not know shows the notification without sound or importance.',
        );
    }

    public function testEveryNotificationTypeLandsOnAPublishedChannel(): void
    {
        $factory = new PushMessageFactory();
        $channel = new \ReflectionMethod($factory, 'channel');

        foreach (NotificationType::cases() as $type) {
            $name = $channel->invoke($factory, $type);

            self::assertContains($name, self::channels(), sprintf('%s is sent on "%s", a channel the app does not create.', $type->value, $name));
        }
    }

    /** @return list<string> */
    private static function channels(): array
    {
        $channels = array_values(array_filter(
            (new \ReflectionClass(PushMessageFactory::class))->getConstants(),
            static fn (string $name) => str_starts_with($name, 'CHANNEL_'),
            \ARRAY_FILTER_USE_KEY,
        ));
        sort($channels);

        return $channels;
    }
}
