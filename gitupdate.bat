git config --global core.autocrlf true
git pull origin master
git add -A
git commit -m "update to v6.3.9"
git push origin master
git tag -a v6.3.9 -m "v6.3.9"
git push origin --tags
pause