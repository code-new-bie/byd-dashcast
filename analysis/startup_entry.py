"""The instrument's start-up entry for the dashcast module, added to the base OS's base/etc/slm-config-modules.xml."""
import hashlib

# The base OS file: two empty SLM modules, no components.
INPUT_SHA = 'edfca97b4d149ab7b9edfde32cf65ac324cece1cea5cd5c3b3ad8925266ae4f2'
ENTRY = (b'    <SLM:component name="dashcast">', b'        <SLM:command>/base/app/dashcast/dashcast-usb</SLM:command>',
         b'        <SLM:args>--boot</SLM:args>', b'        <SLM:stop stop="signal">SIGTERM</SLM:stop>',
         b'        <SLM:depend>usb</SLM:depend>', b'        <SLM:depend>spi-service</SLM:depend>',
         b'        <SLM:depend>tmpfs</SLM:depend>', b'    </SLM:component>')


def build_slm(raw):
    """The base file with the module's one entry appended after its last module; the rest stays as it is."""
    digest = hashlib.sha256(raw).hexdigest()
    if digest != INPUT_SHA:
        raise ValueError('Exact base SLM module file required')
    if b'<SLM:component' in raw or not raw.endswith(b'</SLM:module>\n\n'):
        raise ValueError('Unexpected SLM module structure')
    output = raw + b'\n' + b'\n'.join(ENTRY) + b'\n'
    return output, dict(input_sha256=digest, output_sha256=hashlib.sha256(output).hexdigest())
