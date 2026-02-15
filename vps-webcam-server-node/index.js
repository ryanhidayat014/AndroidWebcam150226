//npm init -y
//npm install express

//npm install pm2 -g
//pm2 start index
//pm2 stop index
//pm2 restart index
//pm2 logs
//http://194.233.84.144:8080/video

const express = require('express');
const http = require('http');

const app = express();
const port = 8080;

let latestFrame = null;
const streamClients = [];

// Middleware untuk logging setiap request yang masuk
app.use((req, res, next) => {
    console.log(`[${new Date().toISOString()}] Request dari ${req.ip} -> ${req.method} ${req.url}`);
    next();
});

// Middleware untuk menerima data gambar mentah
app.use(express.raw({ type: 'image/jpeg', limit: '10mb' }));

/**
 * ENDPOINT: POST /stream
 * Tempat aplikasi Android mengirimkan data gambar.
 */
app.post('/stream', (req, res) => {
    // Pastikan body tidak kosong
    if (!req.body || req.body.length === 0) {
        console.log(' -> Menerima request /stream kosong, diabaikan.');
        return res.sendStatus(400); // Bad Request
    }
    
    console.log(` -> Menerima frame dari Android, ukuran: ${req.body.length} bytes.`);
    latestFrame = req.body;
    
    // Kirim frame ke semua penonton yang terhubung
    if (streamClients.length > 0) {
        console.log(` -> Meneruskan frame ke ${streamClients.length} penonton.`);
        for (const client of streamClients) {
            client.write('--frame\r\n');
            client.write('Content-Type: image/jpeg\r\n');
            client.write(`Content-Length: ${latestFrame.length}\r\n`);
            client.write('\r\n');
            client.write(latestFrame);
            client.write('\r\n');
        }
    }

    // Beri respons OK ke aplikasi Android.
    res.sendStatus(200);
});

/**
 * ENDPOINT: GET /video
 * Tempat browser akan mengambil video stream (MJPEG).
 */
app.get('/video', (req, res) => {
    res.writeHead(200, {
        'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'Pragma': 'no-cache'
    });
    
    streamClients.push(res);
    console.log(` -> Penonton baru terhubung! Total penonton: ${streamClients.length}`);

    // Jika ada frame pertama yang tersedia, kirimkan langsung.
    if (latestFrame) {
        console.log(' -> Mengirim frame pertama ke penonton baru.');
        res.write('--frame\r\n');
        res.write('Content-Type: image/jpeg\r\n');
        res.write(`Content-Length: ${latestFrame.length}\r\n`);
        res.write('\r\n');
        res.write(latestFrame);
        res.write('\r\n');
    }

    // Saat klien menutup koneksi, hapus dari daftar.
    req.on('close', () => {
        const index = streamClients.indexOf(res);
        if (index !== -1) {
            streamClients.splice(index, 1);
        }
        console.log(` -> Penonton terputus. Sisa penonton: ${streamClients.length}`);
    });
});

// Jalankan server
app.listen(port, '0.0.0.0', () => {
    console.log(`Server siap di http://0.0.0.0:${port}`);
    console.log(`Buka http://<IP_VPS_ANDA>:${port}/video di browser untuk melihat stream.`);
    console.log('Menunggu koneksi dari aplikasi Android...');
});