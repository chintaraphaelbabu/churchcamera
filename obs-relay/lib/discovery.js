const SERVICE_NAME = 'ChurchCam Relay';
const SERVICE_TYPE = 'churchcam';

function publishRelayService(bonjour, port) {
  return bonjour.publish({
    name: SERVICE_NAME,
    type: SERVICE_TYPE,
    protocol: 'tcp',
    port: Number(port),
  });
}

module.exports = { publishRelayService };
